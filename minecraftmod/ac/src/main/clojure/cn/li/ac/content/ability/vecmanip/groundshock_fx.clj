(ns cn.li.ac.content.ability.vecmanip.groundshock-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :groundshock
     :runtime :both
     :level-initial-state (fn [] {})
     :hand-initial-state (fn [] {:hand-state {}})
     :channels {:start {:topic :groundshock/fx-start :mode :start :targets [:hand]}
                :update {:topic :groundshock/fx-update :mode :update :targets [:hand]
                         :hand-payload (fn [_ _ p]
                                         {:charge-ticks (long (or (:charge-ticks p) 0))})}
                :perform {:topic :groundshock/fx-perform :mode :perform :targets [:hand :level]
                          :level-payload (fn [_ _ p]
                                           {:affected-blocks (:affected-blocks p)
                                            :broken-blocks (:broken-blocks p)})}
                :end {:topic :groundshock/fx-end :mode :end :targets [:hand]
                      :hand-payload (fn [_ _ p]
                                      {:performed? (boolean (:performed? p))})}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn groundshock-fx-snapshot [] (arc-beam/snapshot :groundshock))

(defn reset-groundshock-fx-for-test! [] (arc-beam/reset-for-test! :groundshock) nil)

(defn clear-groundshock-owner! [owner-key] (arc-beam/clear-owner! :groundshock owner-key) nil)