(ns cn.li.ac.content.ability.meltdowner.light-shield-fx
  "Client FX for LightShield: glowing barrier effect."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-light-shield-fx-runtime-state
  []
  {:effect-state {}})

(defn create-light-shield-fx-runtime
  ([]
   (create-light-shield-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-light-shield-fx-runtime-state))}}]
   {::runtime ::light-shield-fx-runtime
    :state* state*}))

(def ^:dynamic *light-shield-fx-runtime* nil)

(defonce ^:private installed-light-shield-fx-runtime
  (create-light-shield-fx-runtime))

(defn- light-shield-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::light-shield-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-light-shield-fx-runtime
  [runtime f]
  (when-not (light-shield-fx-runtime? runtime)
    (throw (ex-info "Expected light shield FX runtime"
                    {:value runtime})))
  (binding [*light-shield-fx-runtime* runtime]
    (f)))

(defmacro with-light-shield-fx-runtime
  [runtime & body]
  `(call-with-light-shield-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-light-shield-fx-runtime
  []
  (or *light-shield-fx-runtime*
      installed-light-shield-fx-runtime))

(defn- light-shield-fx-state-atom
  []
  (:state* (current-light-shield-fx-runtime)))

(defn- light-shield-fx-state-snapshot
  []
  @(light-shield-fx-state-atom))

(defn- update-light-shield-fx-state!
  [f & args]
  (apply swap! (light-shield-fx-state-atom) f args))

(defn light-shield-fx-snapshot []
  (light-shield-fx-state-snapshot))

(defn reset-light-shield-fx-for-test! []
  (reset! (light-shield-fx-state-atom) (default-light-shield-fx-runtime-state))
  nil)

(defn clear-light-shield-owner! [owner-key]
  (update-light-shield-fx-state! update :effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (update-light-shield-fx-state!
          update :effect-state assoc owner-key*
          (merge base-meta {:active? true :ticks 0}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.shield_on" :volume 0.7 :pitch 1.0}))
      :end
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.shield_off" :volume 0.5 :pitch 0.9})
        (clear-light-shield-owner! owner-key*))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-light-shield-fx-state!
    update :effect-state
    (fn [states]
      (into {}
            (keep (fn [[owner-key st]]
                    (when (:active? st)
                      (let [ticks (inc (long (or (:ticks st) 0)))]
                        (when (zero? (mod ticks 5))
                          (client-particles/queue-particle-effect! (:queue-owner st)
                            {:type :particle :particle-type :end-rod
                             :x 0.0 :y 1.0 :z 0.0
                             :count 3 :speed 0.15
                             :offset-x 0.8 :offset-y 0.8 :offset-z 0.8
                             :relative-to-camera? true}))
                        [owner-key (assoc st :ticks ticks)]))))
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
  (level-effects/register-level-effect! :light-shield
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:light-shield/fx-start :light-shield/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :light-shield/fx-start
        (level-effects/enqueue-level-effect! :light-shield (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :light-shield/fx-end
        (level-effects/enqueue-level-effect! :light-shield (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
