(ns cn.li.ac.content.ability.electromaster.body-intensify-fx
  "Client FX for body-intensify."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private activate-sound-id "my_mod:em.intensify_activate")
(def ^:private local-scripted-effect-key :mcmod/spawn-local-scripted-effect)
(def ^:private intensify-effect-id "intensify_effect")

(defn- on-fx-end
  [ctx-id channel payload]
  (when (:performed? payload)
    (client-sounds/queue-current-sound-effect!
     {:sound-id activate-sound-id
      :volume 0.9
      :pitch 1.0})
    (client-bridge/run-client-effect! local-scripted-effect-key
                                      {:effect-id intensify-effect-id
                                       :ctx-id ctx-id
                                       :channel channel})))

(defn init!
  []
  (fx-spec/register!
    {:id :body-intensify
     :channels {:end {:topic :body-intensify/fx-end
                     :targets [:immediate]
                     :immediate-fn on-fx-end}}})
  nil)
