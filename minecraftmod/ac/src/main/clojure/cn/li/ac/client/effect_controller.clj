(ns cn.li.ac.client.effect-controller
  "AC composition root for legacy skill descriptors on VFX Core.

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
(defonce ^:private screen-flashes* (atom {}))
(def ^:private screen-flash-effect-id :screen-flash-session)
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

(defn- apply-enqueue [state kind handler owner-key payload]
  (if-let [enqueue-state-fn (:enqueue-state-fn handler)]
    (set-handler-state state kind
                       (enqueue-state-fn (handler-state state kind) nil nil owner-key payload))
    state))

(defn- apply-events
  "Deliver every VFX Core signal (combat-core :spawn/:signal, or anything
   else that reaches this instance via vfx-core's stable-key signal path)
   queued for this tick to the content's own enqueue-state-fn, the same
   state-machine entry point content used to reach only through the now-dead
   channel/topic transport. :mode is set from the signal's :event so content
   code keeps its existing (case mode ...) dispatch unchanged."
  [state kind handler owner-key events]
  (reduce (fn [s {:keys [event payload]}]
            (apply-enqueue s kind handler owner-key (assoc payload :mode event)))
          state events))

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

(defn- descriptor [effect-id lifecycle]
  {:id effect-id
   :priority :normal
   :init (fn [_]
           (let [h (effect-handlers effect-id)]
             {:level (initial-value (:initial-state (:level h)))
              :hand (initial-value (:initial-state (:hand h)))}))
   :update (fn [state context]
             (let [h (effect-handlers effect-id)
                   owner-key (:owner (:instance context))
                   events (:events context)
                   next-state (-> state
                                  (apply-events :level (:level h) owner-key events)
                                  (apply-events :hand (:hand h) owner-key events)
                                  (apply-tick :level (:level h))
                                  (apply-tick :hand (:hand h)))]
               ;; :transient effects get one real vfx-core instance per
               ;; activation (register-effect!'s docstring below); unlike
               ;; :singleton's one eternal aggregate instance, each of these
               ;; has to actually be able to end. vfx-core's tick! only
               ;; tears an instance down when THIS :update function returns
               ;; nil for the WHOLE instance (see runtime.clj's tick-instance
               ;; docstring) -- apply-tick/apply-events above only ever
               ;; dissoc a :level/:hand KEY out of this map when a per-track
               ;; tick-state-fn returns nil, never the map itself, so
               ;; next-state is always a (possibly empty) map and this
               ;; function would otherwise never signal a natural end. A
               ;; :transient instance whose last live track just went nil
               ;; would then sit forever as {} or {:hand nil}, still ticked
               ;; and sampled every frame for nothing, until the owner
               ;; disconnects -- one leaked instance per past activation,
               ;; bounded only by create-runtime's :max-instances (silent
               ;; VFX spawn failures once every past cast has piled one up).
               (if (and (= :transient lifecycle)
                        (nil? (:level next-state))
                        (nil? (:hand next-state)))
                 nil
                 next-state)))
   :bounds (fn [_ _] nil)
   :sample (fn [{:keys [sink] :as context}]
             (let [state (:state (:instance context))
                   sampled-state (if (= ::unbound *sample-state*)
                                   state
                                   *sample-state*)]
               (binding [*sample-state* sampled-state]
                 (sample-plan! effect-id sampled-state context sink)
                 (sample-hand! effect-id sampled-state context sink))))
   ;; Only meaningful for :transient effects (real per-instance vfx-core
   ;; teardown -- see core/destroy!'s docstring for why this exists at all:
   ;; a :singleton effect's cleanup still goes through clear-owner! below,
   ;; unchanged). Content opts in per-track by including :destroy-fn in its
   ;; :level/:hand map passed to register-effect! -- same shape as the
   ;; existing :clear-owner-fn, just called once for the whole instance
   ;; instead of per owner-key inside a shared aggregate's state map.
   :destroy (fn [state _context]
              (let [h (effect-handlers effect-id)]
                (when-let [destroy-fn (:destroy-fn (:level h))]
                  (destroy-fn (:level state)))
                (when-let [destroy-fn (:destroy-fn (:hand h))]
                  (destroy-fn (:hand state)))))})

(defn register-effect!
  "Register one descriptor.  A single Runtime instance owns both level and
   first-person state so a skill can never advance twice in one tick.

   :lifecycle defaults to :singleton (the aggregate-instance shape every
   effect used unconditionally before per-effect migration started -- see
   docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md E section). Only :singleton
   effects get an aggregate instance eagerly created here; :transient
   effects spawn/destroy their own real per-owner vfx-core instances via
   dispatch-signal! below, on demand."
  [effect-id {:keys [level hand lifecycle] :or {lifecycle :singleton} :as descriptor-map}]
  (when @frozen?*
    (throw (ex-info "VFX registry is frozen" {:effect-id effect-id})))
  (swap! handlers* update effect-id
         (fn [current]
           {:level (or level (:level current))
            :hand (or hand (:hand current))
            :lifecycle lifecycle}))
  (let [rt (runtime)]
    (when-not (contains? (core/registered-effects rt) effect-id)
      (core/register-effect! rt (assoc (descriptor effect-id lifecycle) :lifecycle lifecycle)))
    (when (= :singleton lifecycle)
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

(defn instance-for-owner
  "State for `owner`'s live :transient instance of effect-id, or nil.

   For callbacks like a :hand transform-fn that vfx-core calls with no
   per-call context of their own (see sample-hand! -- transform-fn takes no
   arguments), so they have no owner to scope a lookup by unless the caller
   resolves and passes one in. Content resolves its own notion of \"owner\"
   (usually the local player's uuid, e.g. via
   cn.li.mcmod.client.platform-bridge/local-player-uuid) and calls this
   directly; :singleton effects have no real per-owner instance to find
   here (they all share the one aggregate instance -- use state-snapshot
   instead)."
  ([effect-id owner] (instance-for-owner effect-id owner :hand))
  ([effect-id owner kind]
   (when-let [instance-id (core/instance-for-owner (runtime) effect-id owner)]
     (handler-state (core/instance-state (runtime) instance-id) kind))))

(defn update-state!
  [effect-id kind f & args]
  (when-let [instance-id (core/instance-for-effect (runtime) effect-id)]
    (core/update-instance-state! (runtime) instance-id
      (fn [state]
        (set-handler-state state kind (apply f (handler-state state kind) args))))))

(defn update-state-for-owner!
  "Write counterpart to instance-for-owner: apply `f` to `owner`'s live
   :transient instance of effect-id, same shape as update-state! but scoped
   to one owner instead of \"the first instance of this effect-id\" (which,
   for a :transient effect with more than one live caster, is an arbitrary
   choice -- level build-plan callbacks are reserved for generic world-space
   presentation effects that need a bounded client query
   sometimes needs to write back into ITS OWN instance's state, not just
   read it: a rescan result computed at sample time, when a query-fn the
   tick-state-fn never receives is available)."
  [effect-id owner kind f & args]
  (when-let [instance-id (core/instance-for-owner (runtime) effect-id owner)]
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
  (swap! screen-flashes* dissoc (str owner-key))
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
   (swap! screen-flashes*
          (fn [states]
            (into {}
                  (keep (fn [[owner state]]
                          (let [remaining (dec (long (or (:remaining-ticks state) 0)))]
                            (when (pos? remaining)
                              [owner (assoc state :remaining-ticks remaining)])))
                        states))))
   (core/tick! (runtime) (merge {:tick-id (quot (System/currentTimeMillis) 50)
                                 :delta-seconds 0.05}
                                context))
   nil))

(defn sample-frame! [context]
  (core/sample-frame! (runtime) (merge {:partial-tick 0.0} context)))

(defn frame-stage [frame-id stage]
  (core/frame-stage (runtime) frame-id stage))
(defn latest-frame-stage [stage]
  (core/latest-frame-stage (runtime) stage))

(defn release-frame! [frame-id]
  (core/release-frame! (runtime) frame-id))

(defonce ^:private unmapped-signal-count* (atom 0))

(defn- apply-screen-flash-signal!
  [signal]
  (when (= screen-flash-effect-id (:effect-id signal))
    (let [owner (some-> (:owner signal) str)
          payload (or (:params signal) {})]
      (when owner
        (case (:op signal)
          (:spawn :signal)
          (swap! screen-flashes* assoc owner
                 {:alpha (double (or (:alpha payload) 0.0))
                  :remaining-ticks (long (or (:duration-ticks payload) 1))})

          :destroy
          (swap! screen-flashes* dissoc owner)
          nil)))
    true))

(defn screen-flash-alpha
  "Current generic screen-flash overlay alpha for one owner.

   This is a presentation read of the neutral :screen-flash-session signal;
   it contains no skill identity or legacy FX lookup."
  [owner]
  (double (or (:alpha (get @screen-flashes* (str owner))) 0.0)))

(defn dispatch-signal!
  "Route one combat VFX signal to its effect's registered vfx-core instance,
   branching on the effect's declared :lifecycle (register-effect! above).

   :singleton effects (not yet migrated to real per-owner instances -- see
   docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md E section) still bypass
   vfx-core's stable-key spawn/event-seq/tombstone path for :spawn/:signal,
   going straight into the one shared aggregate instance: Combat Core's own
   instance-key is per-owner-per-activation ([:combat owner activation-key
   effect-id] — see combat-core/runtime.clj's :vfx op), which is right for
   COMBAT's OWN idempotency, but a :singleton effect's per-owner state
   (arc_beam.clj's owner-keyed maps inside a single effect's state) lives in
   exactly ONE shared vfx-core instance per effect-id. Routing every signal
   for a still-:singleton effect through vfx-core's own dispatch-signal!
   would either (a) spawn a second, duplicate instance per activation that
   the content-facing snapshot/update API can never resolve back to
   (instance-for-effect just returns whichever instance the hash map
   iterates to first), or, if remapped onto one shared instance-key to
   avoid that, (b) collide every player's independent per-session
   event-seq counter onto one shared \"current\" value, silently dropping a
   second player's cast as stale. MSG-COMBAT-RESULT (the only source of
   these signals) is a reliable, ordered push, not a lossy/replayable
   transport, so re-deriving a second idempotency layer here isn't needed
   for :singleton effects: :spawn/:signal go straight to the existing
   aggregate instance's own signal queue every time.

   :transient effects (migrated) use vfx-core's real dispatch-signal!
   unconditionally, passing the full signal straight through -- the
   per-owner-per-activation :instance-key combat-core already stamps on
   every signal is exactly the stable key vfx-core's own spawn/event-seq/
   tombstone rules are built to consume; no bypass needed."
  [signal]
  (let [signal (contract/signal signal)
        _ (apply-screen-flash-signal! signal)
        lifecycle (core/effect-lifecycle (runtime) (:effect-id signal))]
    (if (= :transient lifecycle)
      (core/dispatch-signal! (runtime) signal)
      (case (:op signal)
        (:spawn :signal)
        (if-let [instance-id (core/instance-for-effect (runtime) (:effect-id signal))]
          (core/signal! (runtime) {:instance instance-id} (:event signal) (:params signal))
          (do (swap! unmapped-signal-count* inc)
              (log/warn "VFX signal for an unregistered effect-id" {:effect-id (:effect-id signal)})))
        (core/dispatch-signal! (runtime) signal))))
  nil)

(defn unmapped-signal-count [] @unmapped-signal-count*)

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
    :latest-frame-stage latest-frame-stage
    :release-frame! release-frame!
    :clear-world! clear-world!
    :resource-snapshot resource-snapshot
    :reload-resources! reload-resources!
    :active? active?
    :fov-offset current-fov-offset
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
  (reset! unmapped-signal-count* 0)
  (.clear camera-pitch*)
  nil)
