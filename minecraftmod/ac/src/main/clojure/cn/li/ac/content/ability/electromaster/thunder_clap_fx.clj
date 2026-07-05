(ns cn.li.ac.content.ability.electromaster.thunder-clap-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :thunder-clap
     :initial-state (fn [] {:effect-state {} :impacts {}})
     :channels {:start {:topic :thunder-clap/fx-start :mode :start}
                :update {:topic :thunder-clap/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:ticks (long (or (:ticks p) 0))
                                           :charge-ratio (double (or (:charge-ratio p) 0.0))
                                           :target (get p :target)})}
                :perform {:topic :thunder-clap/fx-perform :mode :perform
                          :level-payload (fn [_ _ p]
                                           {:performed? (boolean (:performed? p))
                                            :ticks (long (or (:ticks p) (:charge-ticks p) 0))
                                            :charge-ratio (double (or (:charge-ratio p) 0.0))
                                            :target (get p :target)})}
                :end {:topic :thunder-clap/fx-end :mode :end
                      :level-payload (fn [_ _ p]
                                       {:performed? (boolean (:performed? p))
                                        :ticks (long (or (:ticks p) (:charge-ticks p) 0))
                                        :charge-ratio (double (or (:charge-ratio p) 0.0))
                                        :target (get p :target)})}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn thunder-clap-fx-snapshot [] (arc-beam/snapshot :thunder-clap))

(defn reset-thunder-clap-fx-for-test! [] (arc-beam/reset-for-test! :thunder-clap) nil)

(defn clear-thunder-clap-owner! [owner-key] (arc-beam/clear-owner! :thunder-clap owner-key) nil)