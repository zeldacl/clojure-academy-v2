(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :penetrate-teleport
     :initial-state (fn [] {:fx-state {}})
     :channels {:start {:topic :penetrate-tp/fx-start :mode :start}
                :update {:topic :penetrate-tp/fx-update :mode :update}
                :perform {:topic :penetrate-tp/fx-perform :mode :perform}
                :end {:topic :penetrate-tp/fx-end :mode :end}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn penetrate-teleport-fx-snapshot [] (arc-beam/snapshot :penetrate-teleport))

(defn reset-penetrate-teleport-fx-for-test! [] (arc-beam/reset-for-test! :penetrate-teleport) nil)

(defn clear-penetrate-teleport-owner! [owner-key] (arc-beam/clear-owner! :penetrate-teleport owner-key) nil)