(ns cn.li.ac.content.ability.electromaster.thunder-bolt-fx
  "Client FX for Thunder Bolt: zigzag electric arc effects."
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :thunder-bolt-strike
     :sound-id "my_mod:em.arc_strong"
     :sound-volume 0.6
     :arc-life 20
     :arc-pattern :strong
     :aoe-points? true
     :channels [{:topic :thunder-bolt/fx-perform :mode :perform}]}))

(defn init! []
  (fx-spec/register! spec)
  nil)

(defn thunder-bolt-fx-snapshot []
  (arc-beam/snapshot :thunder-bolt-strike))

(defn reset-thunder-bolt-fx-for-test! []
  (arc-beam/reset-for-test! :thunder-bolt-strike))

(defn clear-thunder-bolt-owner! [owner-key]
  (arc-beam/clear-owner! :thunder-bolt-strike owner-key))
