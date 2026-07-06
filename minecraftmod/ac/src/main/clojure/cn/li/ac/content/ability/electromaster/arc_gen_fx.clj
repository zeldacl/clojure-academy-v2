(ns cn.li.ac.content.ability.electromaster.arc-gen-fx
  "Client FX for Arc-Gen: short electric arc beam and weak arc sound."
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :arc-gen
     :sound-id "my_mod:em.arc_weak"
     :arc-life 10
     :arc-pattern :weak
     :channels [{:topic :arc-gen/fx-perform :mode :perform
                 :level-payload-fn (fn [_ _ p]
                                     {:start (:start p)
                                      :end (:end p)
                                      :hit-type (:hit-type p)})}]}))

(defn init! []
  (fx-spec/register! spec)
  nil)

(defn arc-gen-fx-snapshot []
  (arc-beam/snapshot :arc-gen))

(defn reset-arc-gen-fx-for-test! []
  (arc-beam/reset-for-test! :arc-gen))

(defn clear-arc-gen-owner! [owner-key]
  (arc-beam/clear-owner! :arc-gen owner-key))
