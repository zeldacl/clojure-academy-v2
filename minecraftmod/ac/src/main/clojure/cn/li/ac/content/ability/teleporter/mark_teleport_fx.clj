(ns cn.li.ac.content.ability.teleporter.mark-teleport-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :mark-teleport
     :initial-state (fn [] {:effect-state {}})
     :channels {:start {:topic :mark-teleport/fx-start :mode :start}
                :update {:topic :mark-teleport/fx-update :mode :update
                         :level-payload (fn [_ _ p] {:target (:target p) :distance (double (or (:distance p) 0.0))})}
                :end {:topic :mark-teleport/fx-end :mode :end}
                :perform {:topic :mark-teleport/fx-perform :mode :perform
                          :level-payload (fn [_ _ p] {:target (:target p) :distance (double (or (:distance p) 0.0))})}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn mark-teleport-fx-snapshot [] (arc-beam/snapshot :mark-teleport))

(defn reset-mark-teleport-fx-for-test! [] (arc-beam/reset-for-test! :mark-teleport) nil)

(defn clear-mark-teleport-owner! [owner-key] (arc-beam/clear-owner! :mark-teleport owner-key) nil)