(ns cn.li.ac.content.ability.vecmanip.vec-deviation-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :vec-deviation
     :initial-state (fn [] {:effect-state {} :wave-effects {}})
     :channels {:start {:topic :vec-deviation/fx-start :mode :start}
                :end {:topic :vec-deviation/fx-end :mode :end}
                :stop-entity {:topic :vec-deviation/fx-stop-entity :mode :stop-entity
                              :level-payload (fn [_ _ p]
                                               {:x (double (or (:x p) 0.0))
                                                :y (double (or (:y p) 0.0))
                                                :z (double (or (:z p) 0.0))
                                                :marked? (boolean (:marked? p))})}
                :play {:topic :vec-deviation/fx-play :mode :play
                       :level-payload (fn [_ _ p]
                                        {:x (double (or (:x p) 0.0))
                                         :y (double (or (:y p) 0.0))
                                         :z (double (or (:z p) 0.0))})}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn vec-deviation-fx-snapshot [] (arc-beam/snapshot :vec-deviation))

(defn reset-vec-deviation-fx-for-test! [] (arc-beam/reset-for-test! :vec-deviation) nil)

(defn clear-vec-deviation-owner! [owner-key] (arc-beam/clear-owner! :vec-deviation owner-key) nil)