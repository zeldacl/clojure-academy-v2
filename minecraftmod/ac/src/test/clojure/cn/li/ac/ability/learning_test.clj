(ns cn.li.ac.ability.learning-test
  "Integration checks against loaded skill registry (cn.li.ac.content.ability)."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.server.service.learning :as lrn]
            [cn.li.ac.content.ability]))

(deftest integration-arc-gen-learn-flow-test
  (testing "arc-gen with content registry: conditions, learn-skill, optional level-up"
    (let [d0 (-> (ad/new-ability-data)
                 (assoc :category-id :electromaster :level 3 :level-progress 10.0))
          {:keys [pass?]} (lrn/check-all-conditions :arc-gen d0 3 :advanced)
          {:keys [data]} (lrn/learn-skill d0 "u" :arc-gen)
          {:keys [event]} (lrn/level-up data "u")]
      (is (true? pass?) "arc-gen should be learnable at level 3 with :advanced")
      (is (contains? (:learned-skills data) :arc-gen) "learned skill should be recorded")
      (is (or (nil? event) (= :ability/level-change (:event/type event)))
          "level-up event shape when progression allows level-up"))))
