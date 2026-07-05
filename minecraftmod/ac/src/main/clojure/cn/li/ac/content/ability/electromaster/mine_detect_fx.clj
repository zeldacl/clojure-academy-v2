(ns cn.li.ac.content.ability.electromaster.mine-detect-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :mine-detect
     :initial-state (fn [] {:effect-state {}})
     :channels {:perform {:topic :mine-detect/fx-perform :mode :perform
                         :level-payload (fn [_ _ p]
                                          {:range (:range p)
                                           :advanced? (:advanced? p)
                                           :life-ticks (:life-ticks p)
                                           :rescan-interval (:rescan-interval p)})}
                :end {:topic :mine-detect/fx-end :mode :end}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn mine-detect-fx-snapshot [] (arc-beam/snapshot :mine-detect))

(defn reset-mine-detect-fx-for-test! [] (arc-beam/reset-for-test! :mine-detect) nil)

(defn clear-mine-detect-owner! [owner-key] (arc-beam/clear-owner! :mine-detect owner-key) nil)