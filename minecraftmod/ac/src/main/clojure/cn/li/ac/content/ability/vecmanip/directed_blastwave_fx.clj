(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :directed-blastwave
     :initial-state (fn [] {:effect-state {} :waves {}})
     :channels {:start {:topic :directed-blastwave/fx-start :mode :start}
                :update {:topic :directed-blastwave/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:charge-ticks (long (or (:charge-ticks p) 0))
                                           :punched? (boolean (:punched? p))})}
                :perform {:topic :directed-blastwave/fx-perform :mode :perform
                          :level-payload (fn [_ _ p]
                                           {:pos (:pos p) :look-dir (:look-dir p)
                                            :charge-ticks (long (or (:charge-ticks p) 0))})}
                :end {:topic :directed-blastwave/fx-end :mode :end
                      :level-payload (fn [_ _ p]
                                       {:performed? (boolean (:performed? p))})}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn directed-blastwave-fx-snapshot [] (arc-beam/snapshot :directed-blastwave))

(defn reset-directed-blastwave-fx-for-test! [] (arc-beam/reset-for-test! :directed-blastwave) nil)

(defn clear-directed-blastwave-owner! [owner-key] (arc-beam/clear-owner! :directed-blastwave owner-key) nil)