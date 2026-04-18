(ns cn.li.ac.ability.learning-test
  (:require [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.server.service.learning :as lrn]
            [cn.li.ac.content.ability]
            [cn.li.ac.ability.registry.skill :as skill]))

(defn test-learn-and-level-up
  []
  (let [d0 (-> (ad/new-ability-data)
               (assoc :category-id :electromaster :level 3 :level-progress 10.0))
        {:keys [pass?]} (lrn/check-all-conditions :arc-gen d0 3 :advanced)
  {:keys [data]} (lrn/learn-skill d0 "u" :arc-gen)
  {:keys [event]} (lrn/level-up data "u")]
    (assert pass? "arc-gen should be learnable at level 3")
    (assert (contains? (:learned-skills data) :arc-gen) "learned skill should be recorded")
    (assert (or (nil? event) (= :ability/level-change (:event/type event))) "level-up event shape")))

(defn run-all-tests []
  (println "=== ability learning tests ===")
  (test-learn-and-level-up)
  (println "ok"))
