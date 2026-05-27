(ns cn.li.ac.content.ability.teleporter.location-teleport-fx
  "Client FX for Location Teleport success feedback."
  (:require [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn- enqueue! [{:keys [payload]}]
  (let [{:keys [mode]} payload]
  (when (= mode :perform-success)
    (client-sounds/queue-current-sound-effect!
      {:type :sound
       :sound-id "my_mod:tp.tp"
       :volume 0.5
       :pitch 1.0}))))

(defn- tick! [] nil)

(defn- build-plan [_camera-pos _hand-center-pos _tick] nil)

(defn init! []
  (level-effects/register-level-effect! :location-teleport
    {:enqueue-event-fn enqueue!
     :tick-fn tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:location-teleport/fx-perform-success]
    (fn [ctx-id channel payload]
      (case channel
        :location-teleport/fx-perform-success
        (level-effects/enqueue-level-effect! :location-teleport
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :perform-success
                  :target (:target payload)
                  :distance (double (or (:distance payload) 0.0))})
          {:ctx-id ctx-id :channel channel})
        nil)))
  nil)