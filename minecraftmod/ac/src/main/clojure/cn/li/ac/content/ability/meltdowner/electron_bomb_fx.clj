(ns cn.li.ac.content.ability.meltdowner.electron-bomb-fx
  "Client FX for ElectronBomb: orbiting ball spawn + beam flash."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-electron-bomb-fx-runtime-state
  []
  {:effect-state {}})

(defn create-electron-bomb-fx-runtime
  ([]
   (create-electron-bomb-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-electron-bomb-fx-runtime-state))}}]
   {::runtime ::electron-bomb-fx-runtime
    :state* state*}))


(defonce ^:private installed-electron-bomb-fx-runtime
  (create-electron-bomb-fx-runtime))

(defonce ^:private electron-bomb-fx-runtime-override* (atom nil))

(defn- electron-bomb-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::electron-bomb-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-electron-bomb-fx-runtime
  [runtime f]
  (when-not (electron-bomb-fx-runtime? runtime)
    (throw (ex-info "Expected electron bomb FX runtime"
                    {:value runtime})))
  (let [prev-override @electron-bomb-fx-runtime-override*]
    (try
      (reset! electron-bomb-fx-runtime-override* runtime)
      (f)
      (finally
        (reset! electron-bomb-fx-runtime-override* prev-override)))))

(defmacro with-electron-bomb-fx-runtime
  [runtime & body]
  `(call-with-electron-bomb-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-electron-bomb-fx-runtime
  []
  (or @electron-bomb-fx-runtime-override*
      @installed-electron-bomb-fx-runtime))

(defn- electron-bomb-fx-state-atom
  []
  (:state* (current-electron-bomb-fx-runtime)))

(defn- electron-bomb-fx-state-snapshot
  []
  @(electron-bomb-fx-state-atom))

(defn- update-electron-bomb-fx-state!
  [f & args]
  (apply swap! (electron-bomb-fx-state-atom) f args))

(defn electron-bomb-fx-snapshot []
  (electron-bomb-fx-state-snapshot))

(defn reset-electron-bomb-fx-for-test! []
  (reset! (electron-bomb-fx-state-atom) (default-electron-bomb-fx-runtime-state))
  nil)

(defn clear-electron-bomb-owner! [owner-key]
  (update-electron-bomb-fx-state! update :effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z dx dy dz start end source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :spawn
      (do
        (update-electron-bomb-fx-state!
          update :effect-state assoc owner-key*
          (merge base-meta
                 {:active? true :ticks 0
                  :x (double (or x 0.0)) :y (double (or y 0.0)) :z (double (or z 0.0))
                  :dx (double (or dx 0.0)) :dy (double (or dy 0.0)) :dz (double (or dz 0.0))}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.eb_spawn" :volume 0.6 :pitch 1.2}))
      :beam
      (do
        (when (and start end)
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
            {:type :particle :particle-type :electric-spark
             :x (double (or (:x end) 0.0))
             :y (double (or (:y end) 0.0))
             :z (double (or (:z end) 0.0))
             :count 8 :speed 0.2
             :offset-x 0.5 :offset-y 0.5 :offset-z 0.5}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.eb_explode" :volume 0.8 :pitch 1.0})
        (clear-electron-bomb-owner! owner-key*))
      :end
      (clear-electron-bomb-owner! owner-key*)
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-electron-bomb-fx-state!
    update :effect-state
    (fn [states]
      (into {}
            (keep (fn [[owner-key st]]
                    (when (:active? st)
                      (let [ticks (inc (long (or (:ticks st) 0)))]
                        (when (zero? (mod ticks 3))
                          (let [angle (* 0.4 (double ticks))
                                ox (* 0.9 (Math/cos angle))
                                oz (* 0.9 (Math/sin angle))]
                            (client-particles/queue-particle-effect! (:queue-owner st)
                              {:type :particle :particle-type :electric-spark
                               :x (+ (:x st) ox) :y (:y st) :z (+ (:z st) oz)
                               :count 1 :speed 0.05
                               :offset-x 0.1 :offset-y 0.1 :offset-z 0.1})))
                        (when-not (> ticks 40)
                          [owner-key (assoc st :ticks ticks)])))))
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
  (let [runtime (create-electron-bomb-fx-runtime)]
    (level-effects/register-level-effect! :electron-bomb
      {:enqueue-event-fn (fn [event]
                           (call-with-electron-bomb-fx-runtime
                             runtime
                             (fn []
                               (enqueue! event))))
       :tick-fn (fn []
                  (call-with-electron-bomb-fx-runtime
                    runtime
                    tick!))
       :build-plan-fn build-plan}))
  (fx-registry/register-fx-channels!
    [:electron-bomb/fx-spawn :electron-bomb/fx-beam :electron-bomb/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :electron-bomb/fx-spawn
        (level-effects/enqueue-level-effect! :electron-bomb
          (merge meta-payload
                 {:mode :spawn
                  :x (:x payload) :y (:y payload) :z (:z payload)
                  :dx (:dx payload) :dy (:dy payload) :dz (:dz payload)})
          {:ctx-id ctx-id :channel channel})
        :electron-bomb/fx-beam
        (level-effects/enqueue-level-effect! :electron-bomb
          (merge meta-payload {:mode :beam :start (:start payload) :end (:end payload)})
          {:ctx-id ctx-id :channel channel})
        :electron-bomb/fx-end
        (level-effects/enqueue-level-effect! :electron-bomb (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
