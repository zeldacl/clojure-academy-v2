(ns cn.li.ac.content.ability.vecmanip.directed-shock-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :directed-shock
     :runtime :hand
     :initial-state (fn [] {:effect-state {}})
     :channels {:start {:topic :directed-shock/fx-start :mode :start :targets [:hand]}
                :perform {:topic :directed-shock/fx-perform :mode :perform :targets [:hand]}
                :end {:topic :directed-shock/fx-end :mode :end :targets [:hand]
                      :hand-payload (fn [_ _ p]
                                      {:performed? (boolean (:performed? p))})}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn directed-shock-fx-snapshot [] (arc-beam/snapshot :directed-shock))

(defn reset-directed-shock-fx-for-test! [] (arc-beam/reset-for-test! :directed-shock) nil)

(defn clear-directed-shock-owner! [owner-key] (arc-beam/clear-owner! :directed-shock owner-key) nil)