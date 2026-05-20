(ns cn.li.ac.ability.config-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]))

(defn- reset-ability-config-fixture [f]
  (try
    (cfg/init-config!)
    (config-reg/set-config-values! config-common/ability-domain cfg/default-values)
    (f)
    (finally
      (config-reg/set-config-values! config-common/ability-domain cfg/default-values))))

(use-fixtures :each reset-ability-config-fixture)

(deftest default-values-contract-test
  (testing "default-values mirrors descriptor defaults"
    (is (= (set (map :key cfg/descriptors))
           (set (keys cfg/default-values))))
    (doseq [{:keys [key default]} cfg/descriptors]
      (is (= default (get cfg/default-values key))))))

(deftest runtime-config-override-and-fallback-test
  (testing "runtime getters use registry overrides"
    (config-reg/set-config-values!
     config-common/ability-domain
     {:damage-scale 2.5
      :attack-player false
      :destroy-blocks false
      :normal-metal-blocks ["custom:metal"]
      :weak-metal-blocks ["custom:weak-metal"]
      :metal-entities ["custom:metal-entity"]
      :init-cp [10.0 11.0 12.0 13.0 14.0]
      :add-cp [100.0 101.0 102.0 103.0 104.0]
      :runtime-cp-consume-per-tick 3.0
      :runtime-overload-per-tick 4.0})
    (is (= 2.5 (cfg/damage-scale)))
    (is (false? (cfg/attack-player-enabled?)))
    (is (false? (cfg/destroy-blocks-enabled?)))
    (is (true? (cfg/is-normal-metal-block? "custom:metal")))
    (is (true? (cfg/is-weak-metal-block? "custom:weak-metal")))
    (is (true? (cfg/is-metal-entity? "custom:metal-entity")))
    (is (= 12.0 (cfg/get-init-cp 3)))
    (is (= 102.0 (cfg/get-add-cp 3)))
    (is (= 114.0 (cfg/max-cp-for-level 3)))
    (is (= 3.0 (cfg/runtime-cp-consume-per-tick)))
    (is (= 4.0 (cfg/runtime-overload-per-tick))))
  (testing "invalid level lists fall back to defaults"
    (config-reg/set-config-values!
     config-common/ability-domain
     {:init-cp [1.0 2.0]
      :add-cp [1.0 2.0 3.0 4.0 5.0]})
    (is (= 1800.0 (cfg/get-init-cp 1)))
    (is (= 3.0 (cfg/get-add-cp 3)))))

(deftest validation-contract-test
  (testing "valid defaults pass"
    (is (nil? (cfg/validate-config!))))
  (testing "invalid values fail validation"
    (config-reg/set-config-values!
     config-common/ability-domain
     {:init-cp [1.0 2.0]
      :normal-metal-blocks [1]
      :cp-recover-speed 0.0
      :damage-scale -1.0
      :runtime-cp-consume-per-tick -1.0})
    (try
      (cfg/validate-config!)
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (let [errors (:errors (ex-data e))]
          (is (some #(= % "init-cp must be a non-negative numeric list with 5 elements") errors))
          (is (some #(= % "normal-metal-blocks must be a string list") errors))
          (is (some #(= % "cp-recover-speed must be positive") errors))
          (is (some #(= % "damage-scale must be positive") errors))
          (is (some #(= % "runtime-cp-consume-per-tick must be non-negative") errors)))))))