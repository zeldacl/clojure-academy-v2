(ns cn.li.ac.ability.client.hud-contract-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.dispatcher :as ctx]))

(deftest slot-visual-uses-active-context-and-authoritative-cooldown-test
  (let [cooldowns (cd-data/set-cooldown (cd-data/new-cooldown-data) :railgun :main 40)]
    (with-redefs [skill-query/get-skill-by-controllable (fn [_ _] :railgun)
                  skill-registry/get-skill (fn [_] {:name "Railgun"})
                  skill-query/get-skill-icon-path (fn [_] "textures/skills/railgun.png")]
      (with-redefs [ctx/get-all-contexts-for-player (fn [& _]
                                                      [{:id "ctx-dead"
                                                        :player-uuid "p1"
                                                        :skill-id :railgun
                                                        :status ctx/STATUS-TERMINATED
                                                        :input-state :active}])]
        (let [slot (first (hud/build-skill-slot-render-data
                           {:active-slots [[:electromaster :railgun]]}
                           320 180 cooldowns "p1"))]
          (is (= :idle (:visual-state slot)))
          (is (true? (:in-cooldown slot)))
          (is (= 40 (:cooldown-remaining slot)))
          (is (= 2.0 (:cooldown-seconds slot)))))
      (with-redefs [ctx/get-all-contexts-for-player (fn [& _]
                                                      [{:id "ctx-live"
                                                        :player-uuid "p1"
                                                        :skill-id :railgun
                                                        :status ctx/STATUS-ALIVE
                                                        :input-state :active}])]
        (let [slot (first (hud/build-skill-slot-render-data
                           {:active-slots [[:electromaster :railgun]]}
                           320 180 cooldowns "p1"))]
          (is (= :active (:visual-state slot)))
          (is (true? (:in-cooldown slot))))))))