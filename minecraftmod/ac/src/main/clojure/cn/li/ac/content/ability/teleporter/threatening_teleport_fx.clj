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

(defn init! [] (fx-spec/register! spec) nil)

(defn threatening-teleport-fx-snapshot [] (arc-beam/snapshot :threatening-teleport))

(defn reset-threatening-teleport-fx-for-test! [] (arc-beam/reset-for-test! :threatening-teleport) nil)

(defn clear-threatening-teleport-owner! [owner-key] (arc-beam/clear-owner! :threatening-teleport owner-key) nil)
