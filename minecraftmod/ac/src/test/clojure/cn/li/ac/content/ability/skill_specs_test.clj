(ns cn.li.ac.content.ability.skill-specs-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.content.ability.electromaster.arc-gen]
            [cn.li.ac.content.ability.electromaster.body-intensify]
            [cn.li.ac.content.ability.electromaster.mine-detect]
            [cn.li.ac.content.ability.electromaster.thunder-clap]
            [cn.li.ac.content.ability.meltdowner.electron-bomb]
            [cn.li.ac.content.ability.meltdowner.mine-ray-basic]
            [cn.li.ac.content.ability.meltdowner.mine-ray-luck]
            [cn.li.ac.content.ability.meltdowner.rad-intensify]
            [cn.li.ac.content.ability.teleporter.flashing]
            [cn.li.ac.content.ability.teleporter.flesh-ripping]
            [cn.li.ac.content.ability.teleporter.mark-teleport]
            [cn.li.ac.content.ability.teleporter.shift-teleport]
            [cn.li.ac.content.ability.vecmanip.directed-shock]
            [cn.li.ac.content.ability.vecmanip.vec-accel]
            [cn.li.ac.content.ability.vecmanip.vec-deviation]
            [cn.li.ac.content.ability.vecmanip.vec-reflection]))

(def ^:private sampled-skill-ids
  [:arc-gen :body-intensify :mine-detect :thunder-clap
   :electron-bomb :mine-ray-basic :mine-ray-luck :rad-intensify
   :flashing :flesh-ripping :mark-teleport :shift-teleport
   :directed-shock :vec-accel :vec-deviation :vec-reflection])

(deftest representative-skill-specs-contract-test
  (doseq [sid sampled-skill-ids]
    (let [spec (skill/get-skill sid)]
      (is (some? spec) (str sid " should be registered"))
      (is (= sid (:id spec)))
      (is (keyword? (:category-id spec)))
      (is (map? (:actions spec)))
      (is (map? (:cooldown spec)))
      (is (or (nil? (:cost spec)) (map? (:cost spec))) (str sid " :cost shape"))
      (is (vector? (:perform spec)))
      (is (map? (:ops spec)))))
  (testing "selected skills carry cost/cooldown contract"
    (is (contains? (:cost (skill/get-skill :arc-gen)) :down))
    (is (= :manual (get-in (skill/get-skill :flashing) [:cooldown :mode])))
    (is (contains? (skill/get-skill :shift-teleport) :perform))
    ;; vec-deviation is a toggle skill; it may not declare any perform-stage
    ;; payload in :perform/:ops, so just assert it's still a registered spec.
    (is (some? (skill/get-skill :vec-deviation)))))
