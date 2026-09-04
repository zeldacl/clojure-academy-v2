(ns cn.li.ac.ability.skills-catalog-test
  "S8: cn.li.ac.ability.skills-catalog actually loads ac/skills/manifest.edn
   and compiles all 39 ac/skills/*.edn sources (via the SAME real
   cn.li.combat.dsl-vocabulary/cn.li.combat.lib every skills_test.clj
   deftest already proves each file compiles against individually) as one
   assembled catalog, and the 50-registration/39-source split (mine-ray's
   3 variants, brain-course's/mind-course's 4 category variants) resolves
   correctly."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.skills-catalog :as skills-catalog]))

(deftest assembles-all-39-sources-and-50-registrations-test
  (let [catalog (skills-catalog/assemble)]
    (is (= 39 (:source-count catalog)))
    (is (= 50 (:registration-count catalog)))
    (is (= 39 (count (:sources catalog))))
    (is (= 50 (count (:registrations catalog))))
    (is (= 50 (count (:by-id catalog))))))

(deftest every-source-compiles-to-real-ir-test
  (let [catalog (skills-catalog/assemble)]
    (doseq [[source-id source] (:sources catalog)]
      (testing (str source-id)
        (is (= (:id source) (some #(when (= source-id (:source-id %)) source-id)
                                  (:registrations catalog)))
            "every source is reachable from at least one registration")))
    (doseq [{:keys [id ir]} (:registrations catalog)]
      (testing (str id)
        (is (some? ir))
        (is (map? (:entries ir)) "compiled IR has real entry points")))))

(deftest shared-source-registrations-carry-distinct-bindings-test
  (let [catalog (skills-catalog/assemble)
        by-id (:by-id catalog)]
    (testing "mine-ray's 3 registrations share one source but differ in :bindings"
      (is (= :mine-ray (:source-id (get by-id :mine-ray-basic))))
      (is (= :mine-ray (:source-id (get by-id :mine-ray-expert))))
      (is (= (:ir (get by-id :mine-ray-basic)) (:ir (get by-id :mine-ray-expert)))
          "same compiled program, shared from one source")
      (is (not= (get-in by-id [:mine-ray-basic :bindings])
                (get-in by-id [:mine-ray-expert :bindings]))))
    (testing "brain-course's 4 category registrations share one source"
      (is (= :brain-course (:source-id (get by-id :electromaster/brain-course))))
      (is (= :brain-course (:source-id (get by-id :vecmanip/brain-course))))
      (is (not= (get-in by-id [:electromaster/brain-course :bindings])
                (get-in by-id [:vecmanip/brain-course :bindings]))))))

(deftest rejects-a-missing-resource-test
  (is (thrown? clojure.lang.ExceptionInfo
               (skills-catalog/assemble {:manifest "ac/skills/does_not_exist_manifest.edn"}))))
