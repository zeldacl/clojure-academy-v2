(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :jet-engine
     :initial-state (fn [] {:fx-state {}})
     :channels {:start {:topic :jet-engine/fx-start}
                :update {:topic :jet-engine/fx-update}
                :end {:topic :jet-engine/fx-end}
                :trigger-start {:topic :jet-engine/fx-trigger-start}
                :trigger-update {:topic :jet-engine/fx-trigger-update}
                :trigger-end {:topic :jet-engine/fx-trigger-end}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn jet-engine-fx-snapshot [] (arc-beam/snapshot :jet-engine))

(defn reset-jet-engine-fx-for-test! [] (arc-beam/reset-for-test! :jet-engine) nil)

(defn clear-jet-engine-owner! [owner-key] (arc-beam/clear-owner! :jet-engine owner-key) nil)