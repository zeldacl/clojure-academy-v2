(ns cn.li.ac.content.ability.teleporter.location-teleport-fx
  "Client FX for Location Teleport success feedback."
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn- default-location-teleport-fx-runtime-state
  []
  {})

(defn- enqueue!
  [store {:keys [payload]}]
  (let [{:keys [mode]} payload]
    (when (= mode :perform-success)
      (client-sounds/queue-current-sound-effect!
        {:type :sound
         :sound-id "my_mod:tp.tp"
         :volume 0.5
         :pitch 1.0}))
    (or store (default-location-teleport-fx-runtime-state))))

(defn- tick!
  [store]
  (or store (default-location-teleport-fx-runtime-state)))

(defn- build-plan [_camera-pos _hand-center-pos _tick] nil)

(defn- on-perform-success! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound
     :sound-id "my_mod:tp.tp"
     :volume 0.5
     :pitch 1.0}))

(defn init! []
  (fx-spec/register!
    {:id :location-teleport
     :channels {:perform-success {:topic :location-teleport/fx-perform-success
                                  :targets [:immediate]
                                  :immediate-fn on-perform-success!}}})
  nil)