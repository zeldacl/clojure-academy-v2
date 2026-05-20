(ns cn.li.ac.ability.learning-test
  "Integration checks against loaded skill registry (cn.li.ac.content.ability)."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.server.service.learning :as lrn]
            [cn.li.ac.content.ability :as ability-content]))

(def ^:private learn-flow-cases
  [{:skill-id :arc-gen
    :category-id :electromaster}
   {:skill-id :electron-bomb
    :category-id :meltdowner}
   {:skill-id :threatening-teleport
    :category-id :teleporter}])

(deftest integration-registry-learn-flow-table-test
  (testing "learn + optional level-up event shape across representative skills"
    (ability-content/init-ability-content!)
    (doseq [{:keys [skill-id category-id]} learn-flow-cases]
      (let [d0 (-> (ad/new-ability-data)
                   (assoc :category-id category-id :level 3 :level-progress 10.0))
            {:keys [pass?]} (lrn/check-all-conditions skill-id d0 3 :advanced)
            {:keys [data]} (lrn/learn-skill d0 "integration-user" skill-id)
            {:keys [event]} (lrn/level-up data "integration-user")]
        (is (true? pass?) (str skill-id " should be learnable at level 3 / :advanced"))
        (is (contains? (:learned-skills data) skill-id)
            (str skill-id " should appear in :learned-skills"))
        (is (or (nil? event) (= :ability/level-change (:event/type event)))
            (str skill-id " level-up branch should be nil or emit :ability/level-change"))))))
