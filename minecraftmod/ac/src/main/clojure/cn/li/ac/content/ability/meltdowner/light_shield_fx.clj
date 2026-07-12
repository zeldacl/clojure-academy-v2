(ns cn.li.ac.content.ability.meltdowner.light-shield-fx
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(defn- shield-end-sound! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id "my_mod:md.shield_loop" :volume 0.35 :pitch 0.95}))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :light-shield
     :initial-state (fn [] {:effect-state {}})
     :channels {:start {:topic :light-shield/fx-start :mode :start}
                :end {:topic :light-shield/fx-end :mode :end
                      :targets [:level :immediate]
                      :immediate-fn shield-end-sound!}}}))

(arc-beam/def-arc-beam-fx :light-shield)
