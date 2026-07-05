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

(defn init! [] (fx-spec/register! spec) nil)

(defn light-shield-fx-snapshot [] (arc-beam/snapshot :light-shield))

(defn reset-light-shield-fx-for-test! [] (arc-beam/reset-for-test! :light-shield) nil)

(defn clear-light-shield-owner! [owner-key] (arc-beam/clear-owner! :light-shield owner-key) nil)
