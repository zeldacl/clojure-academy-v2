(ns cn.li.ac.content.ability.meltdowner.light-shield-fx
  "Client FX for LightShield: glowing barrier effect."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(def ^:private light-shield-effect-id :light-shield)

(defn default-light-shield-fx-runtime-state
  []
  {:effect-state {}})

(defn light-shield-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot light-shield-effect-id)
      (default-light-shield-fx-runtime-state)))

(defn reset-light-shield-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    light-shield-effect-id
    (default-light-shield-fx-runtime-state))
  nil)

(defn clear-light-shield-owner!
  [owner-key]
  (level-effects/update-effect-state!
    light-shield-effect-id
    (fn [store]
      (update (or store (default-light-shield-fx-runtime-state)) :effect-state dissoc owner-key)))
  nil)

(defn- enqueue-state!
  [store {:keys [payload ctx-id channel owner-key]}]
  (let [store* (or store (default-light-shield-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.shield_on" :volume 0.7 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0})))
      :end
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.shield_off" :volume 0.5 :pitch 0.9})
        (update store* :effect-state dissoc owner-key*))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-light-shield-fx-runtime-state))]
    (update store* :effect-state
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
              states)))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  nil)

(defn init!
  []
  (level-effects/register-level-effect! light-shield-effect-id
    {:initial-state (default-light-shield-fx-runtime-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:light-shield/fx-start :light-shield/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
        (case channel
          :light-shield/fx-start
          (level-effects/enqueue-level-effect! light-shield-effect-id
            (merge meta-payload {:mode :start})
            {:ctx-id ctx-id :channel channel})
          :light-shield/fx-end
          (level-effects/enqueue-level-effect! light-shield-effect-id
            (merge meta-payload {:mode :end})
            {:ctx-id ctx-id :channel channel})
          nil))))
  nil)
