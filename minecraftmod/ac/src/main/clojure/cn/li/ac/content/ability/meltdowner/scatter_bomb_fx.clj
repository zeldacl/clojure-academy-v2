(ns cn.li.ac.content.ability.meltdowner.scatter-bomb-fx
  "Client FX for ScatterBomb: ball spawn + scatter beam flashes."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-scatter-bomb-fx-runtime-state
  []
  {:effect-state {}})

(defn create-scatter-bomb-fx-runtime
  ([]
   (create-scatter-bomb-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-scatter-bomb-fx-runtime-state))}}]
   {::runtime ::scatter-bomb-fx-runtime
    :state* state*}))

(def ^:dynamic *scatter-bomb-fx-runtime* nil)

(defn- scatter-bomb-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::scatter-bomb-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-scatter-bomb-fx-runtime
  [runtime f]
  (when-not (scatter-bomb-fx-runtime? runtime)
    (throw (ex-info "Expected scatter-bomb FX runtime"
                    {:value runtime})))
  (binding [*scatter-bomb-fx-runtime* runtime]
    (f)))

(defmacro with-scatter-bomb-fx-runtime
  [runtime & body]
  `(call-with-scatter-bomb-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-scatter-bomb-fx-runtime
  []
  (or *scatter-bomb-fx-runtime*
      (throw (ex-info "Scatter Bomb FX runtime is not bound"
                      {:hint "Bind runtime via call-with-scatter-bomb-fx-runtime or use init! registered handlers"}))))

(defn- scatter-bomb-fx-state-atom
  []
  (:state* (current-scatter-bomb-fx-runtime)))

(defn- scatter-bomb-fx-state-snapshot
  []
  @(scatter-bomb-fx-state-atom))

(defn- update-scatter-bomb-fx-state!
  [f & args]
  (apply swap! (scatter-bomb-fx-state-atom) f args))

(defn scatter-bomb-fx-snapshot []
  (scatter-bomb-fx-state-snapshot))

(defn reset-scatter-bomb-fx-for-test! []
  (reset! (scatter-bomb-fx-state-atom) (default-scatter-bomb-fx-runtime-state))
  nil)

(defn clear-scatter-bomb-owner! [owner-key]
  (update-scatter-bomb-fx-state! update :effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z count start end source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (update-scatter-bomb-fx-state!
          update :effect-state assoc owner-key*
          (merge base-meta {:active? true :ticks 0 :balls 0}))
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id "my_mod:md.sb_charge" :volume 0.5 :pitch 1.0}))
      :ball
      (do
        (update-scatter-bomb-fx-state!
          update :effect-state update owner-key*
          (fn [st]
            (assoc (merge base-meta (or st {:active? true :ticks 0}))
                   :owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id
                   :balls (int (or count 0)))))
        (client-particles/queue-current-particle-effect!
          {:type :particle :particle-type :electric-spark
           :x (double (or x 0.0))
           :y (double (or y 0.0))
           :z (double (or z 0.0))
           :count 4 :speed 0.1
           :offset-x 0.3 :offset-y 0.3 :offset-z 0.3}))
      :beam
      (do
        (when (and start end)
          (client-particles/queue-current-particle-effect!
            {:type :particle :particle-type :electric-spark
             :x (double (or (:x end) 0.0))
             :y (double (or (:y end) 0.0))
             :z (double (or (:z end) 0.0))
             :count 4 :speed 0.15
             :offset-x 0.4 :offset-y 0.4 :offset-z 0.4}))
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id "my_mod:md.eb_explode" :volume 0.4 :pitch 1.2}))
      :end
      (clear-scatter-bomb-owner! owner-key*)
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-scatter-bomb-fx-state!
    update :effect-state
    (fn [states]
      (into {}
            (keep (fn [[owner-key st]]
                    (when (:active? st)
                      [owner-key (assoc st :ticks (inc (long (or (:ticks st) 0))))])))
            states))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  nil)

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (let [runtime (create-scatter-bomb-fx-runtime)]
    (level-effects/register-level-effect! :scatter-bomb
      {:enqueue-event-fn (fn [event]
                           (call-with-scatter-bomb-fx-runtime
                             runtime
                             (fn []
                               (enqueue! event))))
       :tick-fn (fn []
                  (call-with-scatter-bomb-fx-runtime
                    runtime
                    tick!))
       :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:scatter-bomb/fx-start :scatter-bomb/fx-ball :scatter-bomb/fx-beam :scatter-bomb/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :scatter-bomb/fx-start
        (level-effects/enqueue-level-effect! :scatter-bomb (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :scatter-bomb/fx-ball
        (level-effects/enqueue-level-effect! :scatter-bomb
          (merge meta-payload
                 {:mode :ball :x (:x payload) :y (:y payload) :z (:z payload) :count (:count payload)})
          {:ctx-id ctx-id :channel channel})
        :scatter-bomb/fx-beam
        (level-effects/enqueue-level-effect! :scatter-bomb
          (merge meta-payload {:mode :beam :start (:start payload) :end (:end payload)})
          {:ctx-id ctx-id :channel channel})
        :scatter-bomb/fx-end
        (level-effects/enqueue-level-effect! :scatter-bomb (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
