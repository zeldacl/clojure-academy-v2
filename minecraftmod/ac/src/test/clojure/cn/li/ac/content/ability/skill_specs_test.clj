(ns cn.li.ac.content.ability.skill-specs-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.content.ability :as ability-content]))

(def ^:private configured-skill-ids
  skill-config/all-skill-ids)

(deftest configured-skill-specs-contract-test
  (ability-content/init-ability-content!)
  (is (= 38 (count configured-skill-ids)))
  (is (= (set configured-skill-ids)
         (set (map :id (filter #(skill-config/skill-configured? (:id %))
             (skill-query/list-skills))))))
  (doseq [sid configured-skill-ids]
    (let [spec (skill/get-skill sid)]
      (is (some? spec) (str sid " should be registered"))
      (is (= sid (:id spec)))
      (is (keyword? (:category-id spec)))
      (is (boolean? (:enabled spec)))
      (is (boolean? (:controllable? spec)))
      (is (<= 1 (:level spec) 5))
      (is (number? (:damage-scale spec)))
      (is (number? (:cp-consume-speed spec)))
      (is (number? (:overload-consume-speed spec)))
      (is (number? (:exp-incr-speed spec)))
      (is (map? (:actions spec)))
      (is (map? (:cooldown spec)))
      (is (or (nil? (:cost spec)) (map? (:cost spec))) (str sid " :cost shape"))
      (is (vector? (:perform spec)))
      (is (map? (:ops spec)))))
  (testing "selected skills carry cost/cooldown contract"
    (is (contains? (:cost (skill/get-skill :arc-gen)) :down))
    (is (= :manual (get-in (skill/get-skill :flashing) [:cooldown :mode])))
    (is (contains? (skill/get-skill :shift-teleport) :perform))
    (let [blastwave (skill/get-skill :directed-blastwave)]
      (is (some? blastwave))
      (is (= :manual (get-in blastwave [:cooldown :mode])))
      (is (fn? (get-in blastwave [:cost :up :cp])))
      (is (fn? (get-in blastwave [:cost :up :overload])))
      (is (= #{:down! :tick! :up! :abort!}
             (set (keys (:actions blastwave))))))
    (let [mag-movement (skill/get-skill :mag-movement)]
      (is (some? mag-movement))
      (is (= :hold-channel (:pattern mag-movement)))
      (is (fn? (get-in mag-movement [:actions :cost-fail!])))
      (is (fn? (get-in mag-movement [:cost :down :overload])))
      (is (fn? (get-in mag-movement [:cost :tick :cp]))))
    (let [current-charging (skill/get-skill :current-charging)]
      (is (some? current-charging))
      (is (= :hold-channel (:pattern current-charging))))
    ;; vec-deviation is a toggle skill; it may not declare any perform-stage
    ;; payload in :perform/:ops, so just assert it's still a registered spec.
    (is (some? (skill/get-skill :vec-deviation)))))
