(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :plasma-cannon
     :initial-state (fn [] {:effect-state {}})
     :channels {:start {:topic :plasma-cannon/fx-start :mode :start
												:level-payload (fn [_ _ p] {:charge-pos (:charge-pos p)})}
								:update {:topic :plasma-cannon/fx-update :mode :update
												 :level-payload (fn [_ _ p]
																				{:charge-ticks (long (or (:charge-ticks p) 0))
																				 :fully-charged? (boolean (:fully-charged? p))
																				 :charge-pos (:charge-pos p)
																				 :flight-ticks (long (or (:flight-ticks p) 0))
																				 :state (or (:state p) :charging)
																				 :destination (:destination p)})}
								:perform {:topic :plasma-cannon/fx-perform :mode :perform
													:level-payload (fn [_ _ p] {:pos (:pos p)})}
								:end {:topic :plasma-cannon/fx-end :mode :end
											:level-payload (fn [_ _ p]
																			 {:performed? (boolean (:performed? p))})}}}))

(arc-beam/def-arc-beam-fx :plasma-cannon)