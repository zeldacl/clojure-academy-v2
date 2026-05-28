(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx
  "Client FX for VecReflection: double ring + reflection wave billboards."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-vec-reflection-fx-runtime-state
  []
  {:effect-state {}
   :wave-effects {}})

(defn create-vec-reflection-fx-runtime
  ([]
   (create-vec-reflection-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-vec-reflection-fx-runtime-state))}}]
   {::runtime ::vec-reflection-fx-runtime
    :state* state*}))

(def ^:dynamic *vec-reflection-fx-runtime* nil)

(defonce ^:private installed-vec-reflection-fx-runtime (create-vec-reflection-fx-runtime))
(defonce ^:private vec-reflection-fx-runtime-override* (atom nil))

(defn- vec-reflection-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::vec-reflection-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-vec-reflection-fx-runtime
  [runtime f]
  (when-not (vec-reflection-fx-runtime? runtime)
    (throw (ex-info "Expected VecReflection FX runtime"
                    {:value runtime})))
  (let [prev-override @vec-reflection-fx-runtime-override*]
    (try
      (reset! vec-reflection-fx-runtime-override* runtime)
      (f)
      (finally
        (reset! vec-reflection-fx-runtime-override* prev-override)))))

(defmacro with-vec-reflection-fx-runtime
  [runtime & body]
  `(call-with-vec-reflection-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-vec-reflection-fx-runtime
  []
  (or @vec-reflection-fx-runtime-override*
      @installed-vec-reflection-fx-runtime))

(defn- vec-reflection-fx-state-atom
  []
  (:state* (current-vec-reflection-fx-runtime)))

(defn- vec-reflection-fx-state-snapshot
  []
  @(vec-reflection-fx-state-atom))

(defn- update-vec-reflection-fx-state!
  [f & args]
  (apply swap! (vec-reflection-fx-state-atom) f args))

(def ^:private sound-id "my_mod:vecmanip.vec_reflection")

(defn vec-reflection-fx-snapshot
  []
  (vec-reflection-fx-state-snapshot))

(defn reset-vec-reflection-fx-for-test!
  []
  (reset! (vec-reflection-fx-state-atom) (default-vec-reflection-fx-runtime-state))
  nil)

(defn clear-vec-reflection-owner!
  [owner-key]
  (update-vec-reflection-fx-state!
    (fn [state]
      (-> state
          (update :effect-state dissoc owner-key)
          (update :wave-effects dissoc owner-key))))
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z reflected? source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (update-vec-reflection-fx-state! update :effect-state assoc owner-key*
              (merge base-meta {:active? true :ticks 0}))
      :end
      (update-vec-reflection-fx-state! update :effect-state assoc owner-key*
              (merge base-meta {:active? false :ticks 0}))
      :reflect-entity
      (when reflected?
        (let [life (+ 8 (rand-int 6))]
          (update-vec-reflection-fx-state!
            update :wave-effects update owner-key* (fnil conj [])
            (merge base-meta
                   {:x (double x) :y (double y) :z (double z)
                    :ttl life :max-ttl life}))))
      :play
      (client-sounds/queue-current-sound-effect!
        {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
         :x (double x) :y (double y) :z (double z)})
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-vec-reflection-fx-state!
    (fn [{:keys [effect-state wave-effects] :as state}]
      (assoc state
             :effect-state
             (into {}
                   (keep (fn [[owner-key st]]
                           (when (:active? st)
                             [owner-key (update st :ticks (fnil inc 0))])))
                   effect-state)
             :wave-effects
             (into {}
                   (keep (fn [[owner-key xs]]
                           (let [live (->> xs
                                           (map #(update % :ttl dec))
                                           (filter #(pos? (long (:ttl %))))
                                           vec)]
                             (when (seq live)
                               [owner-key live]))))
               wave-effects)))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- double-ring-ops [center ticks]
  (let [base-angle (* 0.15 (double ticks))
        outer-r (+ 0.9 (* 0.06 (Math/sin (* 0.2 (double ticks)))))
        inner-r (* outer-r 0.55)
        y-outer (+ (double (:y center)) 0.2)
        y-inner (+ (double (:y center)) 0.35)
        segments 28
        outer-color {:r 255 :g 210 :b 180 :a 160}
        inner-color {:r 255 :g 180 :b 140 :a 130}]
    (vec
      (concat
        ;; outer ring
        (for [i (range segments)
              :let [a0 (+ base-angle (/ (* 2.0 Math/PI i) segments))
                    a1 (+ base-angle (/ (* 2.0 Math/PI (inc i)) segments))]]
          (ru/line-op
            {:x (+ (:x center) (* outer-r (Math/cos a0)))
             :y y-outer
             :z (+ (:z center) (* outer-r (Math/sin a0)))}
            {:x (+ (:x center) (* outer-r (Math/cos a1)))
             :y y-outer
             :z (+ (:z center) (* outer-r (Math/sin a1)))}
            outer-color))
        ;; inner ring (counter-rotating)
        (for [i (range segments)
              :let [a0 (- (- base-angle) (/ (* 2.0 Math/PI i) segments))
                    a1 (- (- base-angle) (/ (* 2.0 Math/PI (inc i)) segments))]]
          (ru/line-op
            {:x (+ (:x center) (* inner-r (Math/cos a0)))
             :y y-inner
             :z (+ (:z center) (* inner-r (Math/sin a0)))}
            {:x (+ (:x center) (* inner-r (Math/cos a1)))
             :y y-inner
             :z (+ (:z center) (* inner-r (Math/sin a1)))}
            inner-color))))))

(defn- wave-ops [cam-pos {:keys [x y z ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        alpha (int (max 0 (min 255 (* 180.0 life))))
        center {:x (double x) :y (+ (double y) 0.6) :z (double z)}
        right (ru/camera-facing-right-axis center cam-pos)
        up (ru/billboard-up-axis center cam-pos right)
        half-size (+ 0.4 (* 0.6 (- 1.0 life)))
        side (ru/v* right half-size)
        lift (ru/v* up half-size)
        p0 (ru/v+ (ru/v- center side) lift)
        p1 (ru/v+ (ru/v+ center side) lift)
        p2 (ru/v- (ru/v+ center side) lift)
        p3 (ru/v- (ru/v- center side) lift)]
    [(ru/quad-op "my_mod:textures/effects/glow_circle.png"
                 p0 p1 p2 p3
                 {:r 255 :g 200 :b 160 :a alpha})]))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos _tick]
  (let [{:keys [effect-state wave-effects]} (vec-reflection-fx-state-snapshot)
        vr (some (fn [st]
                   (when (and (:active? st)
                              (or (nil? (:source-player-id st))
                                  (nil? (:player-uuid hand-center-pos))
                                  (= (str (:source-player-id st))
                                     (str (:player-uuid hand-center-pos)))))
                     st))
                 (vals effect-state))
        current-waves (mapcat val wave-effects)
        ring-plan (if (and hand-center-pos vr (:active? vr))
                    (double-ring-ops (dissoc hand-center-pos :player-uuid)
                                    (long (or (:ticks vr) 0)))
                    [])
        wave-plan (mapcat #(wave-ops camera-pos %) current-waves)]
    (when (or (seq ring-plan) (seq wave-plan))
      {:ops (vec (concat ring-plan wave-plan))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (let [runtime (create-vec-reflection-fx-runtime)]
    (level-effects/register-level-effect! :vec-reflection
      {:enqueue-event-fn (fn [event]
                           (call-with-vec-reflection-fx-runtime
                             runtime
                             (fn []
                               (enqueue! event))))
       :tick-fn (fn []
                  (call-with-vec-reflection-fx-runtime
                    runtime
                    tick!))
       :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:vec-reflection/fx-start :vec-reflection/fx-end
     :vec-reflection/fx-reflect-entity :vec-reflection/fx-play]
    (fn [ctx-id channel payload]
      (case channel
        :vec-reflection/fx-start
        (level-effects/enqueue-level-effect! :vec-reflection {:mode :start}
                                             {:ctx-id ctx-id :channel channel})
        :vec-reflection/fx-end
        (level-effects/enqueue-level-effect! :vec-reflection {:mode :end}
                                             {:ctx-id ctx-id :channel channel})
        :vec-reflection/fx-reflect-entity
        (level-effects/enqueue-level-effect! :vec-reflection
          {:mode :reflect-entity
           :x (double (or (:x payload) 0.0))
           :y (double (or (:y payload) 0.0))
           :z (double (or (:z payload) 0.0))
           :reflected? (boolean (:reflected? payload))}
          {:ctx-id ctx-id :channel channel})
        :vec-reflection/fx-play
        (level-effects/enqueue-level-effect! :vec-reflection
          {:mode :play
           :x (double (or (:x payload) 0.0))
           :y (double (or (:y payload) 0.0))
           :z (double (or (:z payload) 0.0))}
          {:ctx-id ctx-id :channel channel}))))
  nil)
