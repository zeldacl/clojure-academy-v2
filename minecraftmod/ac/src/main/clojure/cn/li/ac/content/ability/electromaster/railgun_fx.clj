(ns cn.li.ac.content.ability.electromaster.railgun-fx
  (:require [cn.li.ac.ability.client.effects.beam-ops :as beam-ops]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :railgun-shot
     :initial-state (fn [] {:beam-effects {}})
     :channels {:shot {:topic :railgun/fx-shot}
                :reflect {:topic :railgun/fx-reflect}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn railgun-fx-snapshot [] (arc-beam/snapshot :railgun-shot))

(defn reset-railgun-fx-for-test! [] (arc-beam/reset-for-test! :railgun-shot) nil)

(defn clear-railgun-owner! [owner-key] (arc-beam/clear-owner! :railgun-shot owner-key) nil)