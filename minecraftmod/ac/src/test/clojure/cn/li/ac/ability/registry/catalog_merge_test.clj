(ns cn.li.ac.ability.registry.catalog-merge-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.registry.catalog-merge :as merge]))

(deftest combat-metadata-wins-duplicate-ids
  (is (= [{:id :arc-gen :execution :combat-core}
          {:id :brain-course :execution :legacy}]
         (merge/merge-skill-specs
          [{:id :arc-gen :execution :combat-core}]
          [{:id :arc-gen :execution :legacy}
           {:id :pending-skill :execution :legacy}]))))
