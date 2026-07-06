(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(defn- flesh-target-payload [_ctx-id _channel p]
  {:target-x (:target-x p)
   :target-y (:target-y p)
   :target-z (:target-z p)
   :hit? (:hit? p)
   :target-uuid (:target-uuid p)})

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :flesh-ripping
     :initial-state (fn [] {:fx-state {}})
     :channels {:start {:topic :flesh-ripping/fx-start :mode :start}
                :update {:topic :flesh-ripping/fx-update :mode :update :level-payload flesh-target-payload}
                :perform {:topic :flesh-ripping/fx-perform :mode :perform :level-payload flesh-target-payload}
                :end {:topic :flesh-ripping/fx-end :mode :end}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn flesh-ripping-fx-snapshot [] (arc-beam/snapshot :flesh-ripping))

(defn reset-flesh-ripping-fx-for-test! [] (arc-beam/reset-for-test! :flesh-ripping) nil)

(defn clear-flesh-ripping-owner! [owner-key] (arc-beam/clear-owner! :flesh-ripping owner-key) nil)
