(ns cn.li.mcmod.block.dsl-test
  "Unit tests for Block DSL"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.block.dsl :as bdsl]))

(defn- reset-block-registry! [f]
  (reset! bdsl/block-registry {})
  (f)
  (reset! bdsl/block-registry {}))

(use-fixtures :each reset-block-registry!)

(deftest create-block-spec-flat-syntax-test
  (testing "flat syntax maps into nested records"
    (let [spec (bdsl/create-block-spec "test-block"
                                       {:material :stone
                                        :hardness 2.0
                                        :resistance 5.0
                                        :light-level 7})]
      (is (= "test-block" (:id spec)))
      (is (= :stone (get-in spec [:physical :material])))
      (is (= 2.0 (get-in spec [:physical :hardness])))
      (is (= 5.0 (get-in spec [:physical :resistance])))
      (is (= 7 (get-in spec [:rendering :light-level]))))))

(deftest nested-syntax-precedence-test
  (testing "nested config takes precedence over top-level keys"
    (let [spec (bdsl/create-block-spec "nested-priority"
                                       {:material :wood
                                        :hardness 1.0
                                        :physical {:material :metal
                                                   :hardness 8.0}})]
      (is (= :metal (get-in spec [:physical :material])))
      (is (= 8.0 (get-in spec [:physical :hardness]))))))

(deftest register-and-lookup-test
  (testing "register-block! + get-block supports string and keyword ids"
    (let [spec (bdsl/create-block-spec "registry-block" {:material :stone})]
      (bdsl/register-block! spec)
      (is (= "registry-block" (:id (bdsl/get-block "registry-block"))))
      (is (= "registry-block" (:id (bdsl/get-block :registry-block))))
      (is (= #{"registry-block"} (set (bdsl/list-blocks)))))))

(deftest validate-block-spec-invalid-material-test
  (testing "invalid material is rejected"
    (let [spec (bdsl/create-block-spec "bad-material" {:material :not-a-material})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Invalid material"
            (bdsl/validate-block-spec spec))))))

(deftest validate-multiblock-positions-test
  (testing "multi-block positions must include origin"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"must include origin"
          (bdsl/validate-multi-block-positions [{:x 1 :y 0 :z 0}]))))
  (testing "multi-block positions accept vector and map forms"
    (is (true? (bdsl/validate-multi-block-positions [[0 0 0] {:x 1 :y 0 :z 0}])))))

(deftest multiblock-position-helpers-test
  (testing "regular shape expands with relative coordinates"
    (let [positions (bdsl/calculate-multi-block-positions {:width 2 :height 1 :depth 1}
                                                          {:x 0 :y 0 :z 0})]
      (is (= 2 (count positions)))
      (is (some :is-origin? positions))
      (is (= #{0 1} (set (map :relative-x positions))))))
  (testing "normalize-positions shifts minimum corner to origin"
    (let [normalized (bdsl/normalize-positions [{:x 3 :y 5 :z 7}
                                                {:x 4 :y 5 :z 8}])]
      (is (= [{:x 0 :y 0 :z 0}
              {:x 1 :y 0 :z 1}]
             normalized)))))
