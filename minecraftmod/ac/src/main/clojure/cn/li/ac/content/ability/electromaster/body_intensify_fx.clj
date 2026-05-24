(ns cn.li.ac.content.ability.electromaster.body-intensify-fx
  "Client FX for body-intensify."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private activate-sound-id "academy:ability.electromaster.body_intensify")

(defn- on-fx-end
  [_ctx-id _channel payload]
  (when (:performed? payload)
    (client-sounds/queue-sound-effect!
     {:sound-id activate-sound-id
      :volume 0.9
      :pitch 1.0})
    (client-bridge/play-intensify-local-effect!)))

(defn init!
  []
  (fx-registry/register-fx-channel! :body-intensify/fx-end on-fx-end)
  nil)
