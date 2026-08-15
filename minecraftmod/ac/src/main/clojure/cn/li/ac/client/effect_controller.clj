(ns cn.li.ac.client.effect-controller
  "AC composition root for skill descriptors on the Presentation Runtime.

   Skill code supplies pure enqueue/tick/sample callbacks.  This namespace is
   the only AC-owned state adapter; it stores no renderer or Minecraft object."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.runtime.vfx-contract :as contract]
            [cn.li.vfx.runtime :as core])
  (:import [java.util ArrayDeque]))

(defonce ^:private runtime* (atom nil))
(defonce ^:private handlers* (atom {}))
(defonce ^:private ^ArrayDeque camera-pitch* (ArrayDeque. 1024))
(defonce ^:private frozen?* (atom false))
(def ^:dynamic *sample-state*
  "Interpolated aggregate state for the descriptor currently being sampled."
  ::unbound)

(defn runtime []
  (or @runtime*
      (let [created (core/create-runtime {:max-instances 2048 :max-batches 32768})]
        (if (compare-and-set! runtime* nil created) created @runtime*))))

(defn- initial-value [v]
  (if (fn? v) (v) v))

(defn- handler-state [state kind]
  (get state kind))

(defn- effect-handlers [effect-id]
  (get @handlers* effect-id {}))

(defn- set-handler-state [state kind value]
  (if (some? value)
    (assoc state kind value)
    (dissoc state kind)))

(defn- apply-tick [state kind handler]
  (if-let [tick-state-fn (:tick-state-fn handler)]
    (set-handler-state state kind
                       (tick-state-fn (handler-state state kind)))
    state))

(defn- sample-plan! [effect-id state context sink]
  (when-let [{:keys [build-plan-fn]} (:level (effect-handlers effect-id))]
    (when build-plan-fn
      (try
        (let [plan (build-plan-fn (:camera-pos context)
                                  (:hand-center-pos context)
                                  (:tick context)
                                  (:query-nearby-blocks-fn context))
              ops (vec (or (:ops plan) []))]
          (when (or (seq ops) (:local-walk-speed plan))
            ((:emit! sink)
            {:stage :world-after-translucent
              :primitive :mesh
              :material :presentation-world
              :variant :ops
              :count (long (max 1 (count ops)))
              :payload [plan]})))
        (catch Throwable throwable
          (log/error "VFX level sample failed" throwable))))))

(defn- sample-hand! [effect-id state context sink]
  (when-let [{:keys [transform-fn]} (:hand (effect-handlers effect-id))]
    (when transform-fn
      (try
        (when-let [transform (transform-fn)]
          ((:emit! sink)
           {:stage :first-person
            :primitive :first-person
            :material :presentation-first-person
            :variant :transform
            :count 1
            :payload [transform]}))
        (catch Throwable throwable
          (log/error "VFX hand sample failed" throwable))))))

(defn- descriptor [effect-id]
  {:id effect-id
   :priority :normal
   :init (fn [_]
           (let [h (effect-handlers effect-id)]
             {:level (initial-value (:initial-state (:level h)))
              :hand (initial-value (:initial-state (:hand h)))}))
   :update (fn [state _context]
             (let [h (effect-handlers effect-id)]
               (-> state
                   (apply-tick :level (:level h))
                   (apply-tick :hand (:hand h)))))
   :bounds (fn [_ _] nil)
   :sample (fn [{:keys [sink] :as context}]
             (let [state (:state (:instance context))
                   sampled-state (if (= ::unbound *sample-state*)
                                   state
                                   *sample-state*)]
               (binding [*sample-state* sampled-state]
                 (sample-plan! effect-id sampled-state context sink)
                 (sample-hand! effect-id sampled-state context sink))))})

(defn register-effect!
  "Register one descriptor.  A single Runtime instance owns both level and
   first-person state so a skill can never advance twice in one tick."
  [effect-id {:keys [level hand] :as descriptor-map}]
  (when @frozen?*
    (throw (ex-info "VFX registry is frozen" {:effect-id effect-id})))
  (swap! handlers* update effect-id
         (fn [current]
           {:level (or level (:level current))
            :hand (or hand (:hand current))}))
  (let [rt (runtime)]
    (when-not (core/instance-for-effect rt effect-id)
      (core/register-effect! rt (descriptor effect-id))
      (core/ensure-instance! rt effect-id {:owner ::aggregate})))
  nil)

(defn warmup!
  "Eagerly invoke the Presentation Runtime once while the client bootstrap is
  still outside gameplay.  This loads all effect namespaces and surfaces
  malformed descriptors before the first visible frame."
  []
  (core/tick! (runtime) {:tick-id -1 :delta-seconds 0.0})
  (let [frame (core/sample-frame! (runtime) {:frame-id -1 :partial-tick 0.0})]
    (core/release-frame! (runtime) -1)
    (boolean frame)))

(defn freeze! []
  (core/freeze-registry! (runtime))
  (reset! frozen?* true)
  nil)

(defn state-snapshot
  ([effect-id] (state-snapshot effect-id :level))
  ([effect-id kind]
   (if (not= ::unbound *sample-state*)
     (handler-state *sample-state* kind)
     (when-let [instance-id (core/instance-for-effect (runtime) effect-id)]
       (handler-state (core/instance-state (runtime) instance-id) kind)))))

(defn update-state!
  [effect-id kind f & args]
  (when-let [instance-id (core/instance-for-effect (runtime) effect-id)]
    (core/update-instance-state! (runtime) instance-id
      (fn [state]
        (set-handler-state state kind (apply f (handler-state state kind) args))))))

(defn enqueue!
  [effect-id kind ctx-id channel payload & {:keys [owner-key]}]
  (when-let [handler (get (effect-handlers effect-id) kind)]
    (when-let [instance-id (core/instance-for-effect (runtime) effect-id)]
      (core/update-instance-state! (runtime) instance-id
        (fn [state]
          (let [enqueue-state-fn (:enqueue-state-fn handler)
                current (handler-state state kind)
                next-state (if enqueue-state-fn
                             (enqueue-state-fn current ctx-id channel owner-key payload)
                             current)]
            (set-handler-state state kind next-state))))))
  nil)

(defn clear-owner! [owner-key]
  (doseq [[effect-id _] @(:registry (runtime))]
    (when-let [instance-id (core/instance-for-effect (runtime) effect-id)]
      (core/update-instance-state! (runtime) instance-id
        (fn [state]
          (reduce (fn [next-state [kind handler]]
                    (if-let [clear (:clear-owner-fn handler)]
                      (set-handler-state next-state kind
                                         (clear (handler-state next-state kind) owner-key))
                      next-state))
                  state (effect-handlers effect-id))))))
  nil)

(defn active? []
  (letfn [(live-value? [value]
            (and (some? value)
                 (not (and (coll? value) (empty? value)))))]
    (boolean
     (some (fn [[_ instance]]
             (let [state (:state instance)]
               (or (live-value? (:level state))
                   (live-value? (:hand state)))))
           @(:instances (runtime))))))

(defn tick!
  ([] (tick! {:tick-id (quot (System/currentTimeMillis) 50)
              :delta-seconds 0.05}))
  ([context]
   (core/tick! (runtime) (merge {:tick-id (quot (System/currentTimeMillis) 50)
                                 :delta-seconds 0.05}
                                context))
   nil))

(defn sample-frame! [context]
  (core/sample-frame! (runtime) (merge {:partial-tick 0.0} context)))

(defn frame-stage [frame-id stage]
  (core/frame-stage (runtime) frame-id stage))

(defn release-frame! [frame-id]
  (core/release-frame! (runtime) frame-id))

(defn clear-world! [world-id]
  ;; AC keeps one aggregate instance per descriptor.  Strip only payloads
  ;; tagged with the unloading world; descriptors and other worlds survive the
  ;; transition.  This keeps reload/disconnect cleanup O(number of live
  ;; payloads) without leaking a second platform-owned registry.
  (letfn [(strip-world [value]
            (cond
              (and (map? value) (= world-id (:world-id value))) nil
              (map? value)
              (into (empty value)
                    (keep (fn [[k v]]
                            (when-let [clean (strip-world v)] [k clean]))) value)
              (vector? value) (vec (keep strip-world value))
              (seq? value) (doall (keep strip-world value))
              :else value))]
  (doseq [effect-id (core/registered-effects (runtime))]
    (when-let [instance-id (core/instance-for-effect (runtime) effect-id)]
      (core/update-instance-state! (runtime) instance-id
        (fn [state]
          (-> state
              (update :level strip-world)
              (update :hand strip-world))))))
  world-id))

(defn reload-resources! [generation]
  (core/reload-resources! (runtime) generation))

(defn current-fov-offset [player-uuid]
  (reduce max 0.0
          (for [[_ {:keys [level]}] @handlers*
                :let [fov-offset-fn (:fov-offset-fn level)]
                :when fov-offset-fn]
            (double (or (fov-offset-fn player-uuid) 0.0)))))

(defn add-camera-pitch-delta!
  ([delta] (add-camera-pitch-delta! nil delta))
  ([_owner delta]
   (when (< (.size camera-pitch*) 1024)
     (.addLast camera-pitch* [_owner (float delta)]))
   nil))

(defn drain-camera-pitch-deltas!
  ([] (drain-camera-pitch-deltas! nil))
  ([owner]
   (let [out (transient []) remaining (ArrayDeque. 1024)]
    (loop []
      (when-let [entry (.pollFirst camera-pitch*)]
        (if (or (nil? owner) (= owner (first entry)))
          (conj! out (second entry))
          (.addLast remaining entry))
        (recur)))
    (doseq [entry remaining] (.addLast camera-pitch* entry))
    (persistent! out))))

(defn current-hand-transform []
  (some (fn [[_ {:keys [hand]}]]
          (when-let [transform-fn (:transform-fn hand)]
            (transform-fn)))
        @handlers*))

(defn registered-effects []
  (core/registered-effects (runtime)))

(defn handlers-snapshot [] @handlers*)

(defn required-anchors
  "Neutral anchor tokens requested by the AC composition root.  Platforms
   resolve these tokens to immutable snapshots before calling :tick! or
   :sample-frame!; no Minecraft object crosses this boundary."
  []
  #{:camera :local-player :world})

(defn resource-snapshot
  "Return the immutable resource view used by reload/warm-up diagnostics."
  []
   {:generation (core/resource-generation (runtime))
   :effects (vec (sort (map name (registered-effects))))})

(defn vfx-host-api
  "Return the validated opaque VFX host API installed by AC.

   Platform code receives this map through the client bridge and never loads
   this namespace or the VFX runtime directly."
  []
  (contract/validate-host-api
   {:schema-version contract/schema-version
    :required-anchors required-anchors
    :tick! tick!
    :sample-frame! sample-frame!
    :frame-stage frame-stage
    :release-frame! release-frame!
    :clear-world! clear-world!
    :resource-snapshot resource-snapshot
    :reload-resources! reload-resources!
    :active? active?
    :fov-offset current-fov-offset
    :hand-transform current-hand-transform
    :drain-camera-pitch-deltas! drain-camera-pitch-deltas!}))

;; Content effects use these narrow names while their namespaces are migrated
;; to this composition-root Runtime. They are intentionally thin calls into
;; the same state table, never a second registry.
(defn effect-state-snapshot [effect-id] (state-snapshot effect-id :level))
(defn update-effect-state! [effect-id f & args]
  (apply update-state! effect-id :level f args))
(defn reset-level-effect-state-for-test! [effect-id state]
  (update-state! effect-id :level (constantly state)))
(defn clear-effect-owner! [owner-key] (clear-owner! owner-key))
(defn current-effect-owner [] nil)
(defn enqueue-level-effect! [effect-id ctx-id channel payload & {:keys [owner-key]}]
  (enqueue! effect-id :level ctx-id channel payload :owner-key owner-key))
(defn effect-hand-state [effect-id] (state-snapshot effect-id :hand))
(defn update-hand-effect-state! [effect-id f & args]
  (apply update-state! effect-id :hand f args))
(defn reset-hand-effect-state-for-test! [effect-id state]
  (update-state! effect-id :hand (constantly state)))
(defn enqueue-hand-effect! [effect-id ctx-id channel payload & {:keys [owner-key]}]
  (enqueue! effect-id :hand ctx-id channel payload :owner-key owner-key))
(defn clear-owner-camera-pitch-deltas! [owner]
  (drain-camera-pitch-deltas! owner))
(defn smoothstep [^double edge0 ^double edge1 ^double x]
  (let [t (max 0.0 (min 1.0 (/ (- x edge0) (- edge1 edge0))))]
    (* t t (- 3.0 (* 2.0 t)))))
(defn sample-curve [curve ^double t]
  (cond
    (<= t (ffirst curve)) (second (first curve))
    (>= t (first (last curve))) (second (last curve))
    :else (let [[[x0 y0] [x1 y1]]
                (first (filter (fn [[[a _] [b _]]] (and (<= a t) (< t b)))
                               (partition 2 1 curve)))]
            (+ y0 (* (/ (- t x0) (- x1 x0)) (- y1 y0))))))

(defn any-level-effect-active? [] (active?))
(defn reset-effect-failure-reports-for-test! [] nil)

(defn reset-for-test!
  []
  (reset! runtime* nil)
  (reset! handlers* {})
  (reset! frozen?* false)
  (.clear camera-pitch*)
  nil)
