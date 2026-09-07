(ns cn.li.ac.ability.skills-catalog-test
  "The V3 catalog loads one self-contained document per public skill id.
   Registration bindings are stored on the document itself, so the editor
   and runtime consume the same immutable source without a manifest layer."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.skills-catalog :as skills-catalog]))

(deftest assembles-all-v3-documents-test
  (let [catalog (skills-catalog/assemble)]
    ;; Test resources intentionally contribute one editor smoke document;
    ;; production resources contain exactly the 50 documents below.
    (is (= 51 (:source-count catalog)))
    (is (= 51 (:registration-count catalog)))
    (is (= 51 (count (:sources catalog))))
    (is (= 51 (count (:registrations catalog))))
    (is (= 51 (count (:by-id catalog))))
    (is (contains? (:by-id catalog) :ac.test/catalog-smoke))
    (is (every? #(= (:id %) (:id (:document %))) (:registrations catalog)))))

(deftest every-v3-document-compiles-to-real-ir-test
  (let [catalog (skills-catalog/assemble)]
    (doseq [[source-id source] (:sources catalog)]
      (testing (str source-id)
        (is (= source-id (:id source)))
        (is (= :ac/skill-v3 (:schema source)))))
    (doseq [{:keys [id ir document]} (:registrations catalog)]
      (testing (str id)
        (is (= id (:id document)))
        (is (some? ir))
        (is (map? (:entries ir)) "compiled IR has real entry points")))))

(deftest public-registrations-preserve-editor-metadata-test
  (let [catalog (skills-catalog/assemble)
        by-id (:by-id catalog)]
    (testing "mine-ray variants are independent documents with distinct presentation"
      (is (not= (:id (:document (get by-id :mine-ray-basic)))
                (:id (:document (get by-id :mine-ray-expert)))))
      (is (= :basic (get-in by-id [:mine-ray-basic :document :presentation :variant])))
      (is (= :expert (get-in by-id [:mine-ray-expert :document :presentation :variant])))
      (is (not= (get-in by-id [:mine-ray-basic :document :presentation])
                (get-in by-id [:mine-ray-expert :document :presentation]))))
    (testing "category variants carry category on the document"
      (is (= :electromaster
             (get-in by-id [:electromaster/brain-course :document :skill :category])))
      (is (= :vecmanip
             (get-in by-id [:vecmanip/brain-course :document :skill :category]))))))
