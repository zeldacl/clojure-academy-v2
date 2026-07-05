(ns cn.li.ac.content.ability.meltdowner.rad-intensify-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :rad-intensify-mark
     :initial-state (fn [] {:marks {}})
     :channels {:mark {:topic :rad-intensify/fx-mark}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn rad-intensify-fx-snapshot [] (arc-beam/snapshot :rad-intensify-mark))

(defn reset-rad-intensify-fx-for-test! [] (arc-beam/reset-for-test! :rad-intensify-mark) nil)

(defn clear-rad-intensify-owner! [owner-key] (arc-beam/clear-owner! :rad-intensify-mark owner-key) nil)