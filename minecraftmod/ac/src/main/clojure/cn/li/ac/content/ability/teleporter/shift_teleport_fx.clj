(ns cn.li.ac.content.ability.teleporter.shift-teleport-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :shift-teleport
     :initial-state (fn [] {:fx-state {}})
     :channels {:start {:topic :shift-teleport/fx-start :mode :start}
                :update {:topic :shift-teleport/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:x (:x p) :y (:y p) :z (:z p)
                                           :target-count (:target-count p) :target-hit? (:target-hit? p)
                                           :hand-valid? (:hand-valid? p)})}
                :perform {:topic :shift-teleport/fx-perform :mode :perform
                          :level-payload (fn [_ _ p]
                                           {:x (:x p) :y (:y p) :z (:z p)
                                            :from-x (:from-x p) :from-y (:from-y p) :from-z (:from-z p)})}
                :end {:topic :shift-teleport/fx-end :mode :end}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn shift-teleport-fx-snapshot [] (arc-beam/snapshot :shift-teleport))

(defn reset-shift-teleport-fx-for-test! [] (arc-beam/reset-for-test! :shift-teleport) nil)

(defn clear-shift-teleport-owner! [owner-key] (arc-beam/clear-owner! :shift-teleport owner-key) nil)