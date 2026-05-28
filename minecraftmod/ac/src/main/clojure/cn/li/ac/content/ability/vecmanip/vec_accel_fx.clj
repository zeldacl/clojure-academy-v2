(ns cn.li.ac.content.ability.vecmanip.vec-accel-fx
  "Client FX for VecAccel: trajectory preview ribbon."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-vec-accel-fx-runtime-state
  []
  {:effect-state {}})

(defn create-vec-accel-fx-runtime
  ([]
   (create-vec-accel-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-vec-accel-fx-runtime-state))}}]
   {::runtime ::vec-accel-fx-runtime
    :state* state*}))

(def ^:dynamic *vec-accel-fx-runtime* nil)

(defn- vec-accel-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::vec-accel-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-vec-accel-fx-runtime
  [runtime f]
  (when-not (vec-accel-fx-runtime? runtime)
    (throw (ex-info "Expected VecAccel FX runtime"
                    {:value runtime})))
  (binding [*vec-accel-fx-runtime* runtime]
    (f)))

(defmacro with-vec-accel-fx-runtime
  [runtime & body]
  `(call-with-vec-accel-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-vec-accel-fx-runtime
  []
  (or *vec-accel-fx-runtime*
      (throw (ex-info "VecAccel FX runtime is not bound"
                      {:hint "Bind runtime via call-with-vec-accel-fx-runtime or use init! registered handlers"}))))

(defn- vec-accel-fx-state-atom
  []
  (:state* (current-vec-accel-fx-runtime)))

(defn- vec-accel-fx-state-snapshot
  []
  @(vec-accel-fx-state-atom))

(defn- update-vec-accel-fx-state!
  [f & args]
  (apply swap! (vec-accel-fx-state-atom) f args))

(def ^:private sound-id "my_mod:vecmanip.vec_accel")

(defn vec-accel-fx-snapshot
  []
  (vec-accel-fx-state-snapshot))

(defn reset-vec-accel-fx-for-test!
  []
  (reset! (vec-accel-fx-state-atom) (default-vec-accel-fx-runtime-state))
  nil)

(defn clear-vec-accel-owner!
  [owner-key]
  (update-vec-accel-fx-state! update :effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode charge-ticks can-perform? look-dir init-vel source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (update-vec-accel-fx-state!
        update :effect-state assoc owner-key*
        (merge base-meta
               {:active? true :charge-ticks 0
                :can-perform? false
                :look-dir {:x 0.0 :y 0.0 :z 1.0}
                :init-vel {:x 0.0 :y 0.0 :z 1.0}}))
      :update
      (update-vec-accel-fx-state!
        update :effect-state update owner-key*
        (fn [st]
          (assoc (merge base-meta (or st {:active? true}))
                 :owner-key owner-key*
                 :ctx-id ctx-id
                 :channel channel
                 :source-player-id source-player-id
                 :world-id world-id
                 :active? true
                 :charge-ticks (long (or charge-ticks 0))
                 :can-perform? (boolean can-perform?)
                 :look-dir (or look-dir {:x 0.0 :y 0.0 :z 1.0})
                 :init-vel (or init-vel {:x 0.0 :y 0.0 :z 1.0}))))
      :perform
      (client-sounds/queue-current-sound-effect!
        {:type :sound :sound-id sound-id :volume 0.35 :pitch 1.0})
      :end
      (update-vec-accel-fx-state! update :effect-state assoc owner-key* (merge base-meta {:active? false}))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick (no-op, state is driven by updates)
;; ---------------------------------------------------------------------------

(defn- tick! [] nil)

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- trajectory-ops [camera-pos effect]
  (when (and effect (:active? effect))
    (let [init-vel  (or (:init-vel effect) {:x 0.0 :y 0.0 :z 1.0})
          look-dir  (or (:look-dir effect) {:x 0.0 :y 0.0 :z 1.0})
          can-do?   (boolean (:can-perform? effect))
          px        (double (:x camera-pos))
          py        (- (double (:y camera-pos)) 1.62)
          pz        (double (:z camera-pos))
          lx        (double (:x look-dir))
          ly        (double (:y look-dir))
          lz        (double (:z look-dir))
          horiz-len (Math/sqrt (+ (* lx lx) (* lz lz)))
          safe-h    (max 1.0e-8 horiz-len)
          rot-x     (* (/ lz safe-h) -0.08)
          rot-z     (* (/ (- lx) safe-h) -0.08)
          off-x     (- rot-x (* lx 0.12))
          off-y     (- 1.56   (* ly 0.12))
          off-z     (- rot-z  (* lz 0.12))
          start-pos {:x (+ px off-x) :y (+ py off-y) :z (+ pz off-z)}
          h         0.02
          dt        0.02]
      (loop [pos   start-pos
             vel   {:x (double (:x init-vel))
                    :y (double (:y init-vel))
                    :z (double (:z init-vel))}
             prev  nil
             ops   (transient [])
             idx   0]
        (let [vel2  {:x (* (double (:x vel)) 0.98)
                     :y (* (double (:y vel)) 0.98)
                     :z (* (double (:z vel)) 0.98)}
              pos2  {:x (+ (double (:x pos)) (* (double (:x vel2)) dt))
                     :y (+ (double (:y pos)) (* (double (:y vel2)) dt))
                     :z (+ (double (:z pos)) (* (double (:z vel2)) dt))}
              vel3  (assoc vel2 :y (- (double (:y vel2)) (* dt 1.9)))]
          (when (and prev (< idx 99))
            (let [a-int (fx-beam/fade-alpha idx)]
              (when (> a-int 0)
                (let [color (fx-beam/rgba
                              (if can-do?
                                {:r 255 :g 255 :b 255}
                                {:r 255 :g  51 :b  51})
                              a-int)
                      p0 {:x (double (:x prev)) :y (+ (double (:y prev)) h) :z (double (:z prev))}
                      p1 {:x (double (:x pos))  :y (+ (double (:y pos))  h) :z (double (:z pos))}
                      p2 {:x (double (:x pos))  :y (- (double (:y pos))  h) :z (double (:z pos))}
                      p3 {:x (double (:x prev)) :y (- (double (:y prev)) h) :z (double (:z prev))}]
                  (conj! ops (fx-beam/glow-line-quad-op p0 p1 p2 p3 color))))))
          (if (>= idx 99)
            (persistent! ops)
            (recur pos2 vel3 pos ops (inc idx))))))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [ops (mapcat #(trajectory-ops camera-pos %)
                    (filter :active? (vals (:effect-state (vec-accel-fx-state-snapshot)))))]
    (when (seq ops)
      {:ops (vec ops)})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (let [runtime (create-vec-accel-fx-runtime)]
    (level-effects/register-level-effect! :vec-accel
      {:enqueue-event-fn (fn [event]
                           (call-with-vec-accel-fx-runtime
                             runtime
                             (fn []
                               (enqueue! event))))
       :tick-fn (fn []
                  (call-with-vec-accel-fx-runtime
                    runtime
                    tick!))
       :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:vec-accel/fx-start :vec-accel/fx-update :vec-accel/fx-perform :vec-accel/fx-end]
        (fn [ctx-id channel payload]
      (case channel
        :vec-accel/fx-start
       (level-effects/enqueue-level-effect! :vec-accel
                   (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                     {:mode :start})
                   {:ctx-id ctx-id :channel channel})
        :vec-accel/fx-update
        (level-effects/enqueue-level-effect! :vec-accel
         (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
           {:mode :update
            :charge-ticks (long (or (:charge-ticks payload) 0))
            :can-perform? (boolean (:can-perform? payload))
            :look-dir (:look-dir payload)
            :init-vel (:init-vel payload)})
         {:ctx-id ctx-id :channel channel})
        :vec-accel/fx-perform
       (level-effects/enqueue-level-effect! :vec-accel
                   (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                     {:mode :perform})
                   {:ctx-id ctx-id :channel channel})
        :vec-accel/fx-end
        (level-effects/enqueue-level-effect! :vec-accel
         (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
           {:mode :end :performed? (boolean (:performed? payload))})
         {:ctx-id ctx-id :channel channel}))))
  nil)
