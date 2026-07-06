(ns cn.li.ac.content.ability.electromaster.mag-movement-fx
  (:require [cn.li.ac.ability.client.effects.beam-ops :as beam-ops]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :mag-movement
     :initial-state (fn [] {:effect-state {}})
     :channels {:start {:topic :mag-movement/fx-start :mode :start}
                :update {:topic :mag-movement/fx-update :mode :update}
                :end {:topic :mag-movement/fx-end :mode :end}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn mag-movement-fx-snapshot [] (arc-beam/snapshot :mag-movement))

(defn reset-mag-movement-fx-for-test! [] (arc-beam/reset-for-test! :mag-movement) nil)

(defn clear-mag-movement-owner! [owner-key] (arc-beam/clear-owner! :mag-movement owner-key) nil)