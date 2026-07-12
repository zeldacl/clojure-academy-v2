(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(defn- target-payload [_ctx-id _channel p]
  {:target-x (:target-x p) :target-y (:target-y p) :target-z (:target-z p) :hit? (:hit? p)})

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :threatening-teleport
     :initial-state (fn [] {:fx-state {}})
     :channels {:start {:topic :threatening-tp/fx-start :mode :start}
                :update {:topic :threatening-tp/fx-update :mode :update :level-payload target-payload}
                :perform {:topic :threatening-tp/fx-perform :mode :perform :level-payload target-payload}
                :end {:topic :threatening-tp/fx-end :mode :end}}}))

(arc-beam/def-arc-beam-fx :threatening-teleport)
