(ns cn.li.ac.config.gameplay-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.mcmod.config.registry :as config-reg]))

(deftest gameplay-default-branch-test
  (try
    (gameplay/init-config!)
    (is (true? (gameplay/attack-player-enabled?)))
    (is (true? (gameplay/destroy-blocks-enabled?)))
    (is (= 1800 (gameplay/get-init-cp 0)))
    (is (= 900 (gameplay/get-add-cp 1)))
    (is (true? (boolean (gameplay/is-metal-block? "minecraft:iron_block"))))
    (is (false? (boolean (gameplay/is-metal-block? "minecraft:dirt"))))
    (finally
      (config-reg/set-config-values! config-common/gameplay-domain gameplay/default-values))))

(deftest gameplay-registry-override-test
  (try
    (gameplay/init-config!)
    (config-reg/set-config-values!
     config-common/gameplay-domain
     {:attack-player false
      :destroy-blocks false
      :init-cp [100 101 102 103 104 105]
      :add-cp [200 201 202 203 204 205]
      :normal-metal-blocks ["custom:metal"]})
    (is (false? (gameplay/attack-player-enabled?)))
    (is (false? (gameplay/destroy-blocks-enabled?)))
    (is (= 102 (gameplay/get-init-cp 2)))
    (is (= 203 (gameplay/get-add-cp 3)))
    (is (true? (gameplay/is-metal-block? "custom:metal")))
    (is (false? (gameplay/is-metal-block? "custom:stone")))
    (finally
      (config-reg/set-config-values! config-common/gameplay-domain gameplay/default-values))))

(deftest gameplay-validate-contract-test
  (testing "valid defaults pass"
    (try
      (gameplay/init-config!)
      (is (= nil (gameplay/validate-config!)))
      (finally
        (config-reg/set-config-values! config-common/gameplay-domain gameplay/default-values))))
  (testing "invalid numeric registry values fail validation"
    (try
      (gameplay/init-config!)
      (config-reg/set-config-values!
       config-common/gameplay-domain
       {:cp-recover-speed 0
        :overload-recover-speed -1
        :damage-scale 0})
      (try
        (gameplay/validate-config!)
        (is false)
        (catch clojure.lang.ExceptionInfo e
          (let [errors (:errors (ex-data e))]
            (is (some #(= % "cp-recover-speed must be positive") errors))
            (is (some #(= % "overload-recover-speed must be positive") errors))
            (is (some #(= % "damage-scale must be positive") errors)))))
      (finally
        (config-reg/set-config-values! config-common/gameplay-domain gameplay/default-values)))))
