(ns cn.li.ac.config.gameplay-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.config.gameplay :as gameplay]))

(deftest gameplay-default-branch-test
  (binding [gameplay/*config-bridge* nil]
    (is (true? (gameplay/analysis-enabled?)))
    (is (true? (gameplay/destroy-blocks-enabled?)))
    (is (= 1800 (gameplay/get-init-cp 0)))
    (is (= 900 (gameplay/get-add-cp 1)))
    (is (true? (boolean (gameplay/is-metal-block? "minecraft:iron_block"))))
    (is (false? (boolean (gameplay/is-metal-block? "minecraft:dirt"))))))

(deftest gameplay-bridge-branch-test
  (binding [gameplay/*config-bridge*
            {:analysis-enabled? (fn [] false)
             :destroy-blocks? (fn [] false)
             :get-init-cp (fn [level] (+ 100 level))
             :get-add-cp (fn [level] (+ 200 level))
             :is-metal-block? (fn [bid] (= bid "custom:metal"))
             :get-cp-recover-speed (fn [] 2.0)
             :get-overload-recover-speed (fn [] 3.0)
             :get-damage-scale (fn [] 4.0)}]
    (is (false? (gameplay/analysis-enabled?)))
    (is (false? (gameplay/destroy-blocks-enabled?)))
    (is (= 102 (gameplay/get-init-cp 2)))
    (is (= 203 (gameplay/get-add-cp 3)))
    (is (true? (gameplay/is-metal-block? "custom:metal")))
    (is (false? (gameplay/is-metal-block? "custom:stone")))))

(deftest gameplay-validate-contract-test
  (testing "valid defaults pass"
    (binding [gameplay/*config-bridge* nil]
      (is (= nil (gameplay/validate-config!)))))
  (testing "invalid numeric bridge values fail validation"
    (binding [gameplay/*config-bridge*
              {:get-cp-recover-speed (fn [] 0)
               :get-overload-recover-speed (fn [] -1)
               :get-damage-scale (fn [] 0)}]
      (try
        (gameplay/validate-config!)
        (is false)
        (catch clojure.lang.ExceptionInfo e
          (let [errors (:errors (ex-data e))]
            (is (some #(= % "cp-recover-speed must be positive") errors))
            (is (some #(= % "overload-recover-speed must be positive") errors))
            (is (some #(= % "damage-scale must be positive") errors))))))))
