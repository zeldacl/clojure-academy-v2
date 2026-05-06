(ns cn.li.ac.ability.state.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.state.store :as store]
            [cn.li.mcmod.platform.ability :as pability]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(deftest ac-player-ability-store-roundtrip-test
  (let [st (store/ac-player-ability-store)
        u "player-uuid-1"]
    (is (nil? (pability/ability-get-category st u)))
    (pability/ability-set-category! st u :electromaster)
    (is (= :electromaster (pability/ability-get-category st u)))
    (is (false? (pability/ability-is-learned? st u :arc-gen)))
    (pability/ability-learn-skill! st u :arc-gen)
    (is (true? (pability/ability-is-learned? st u :arc-gen)))
    (is (= 0.0 (pability/ability-get-skill-exp st u :arc-gen)))
    (pability/ability-set-skill-exp! st u :arc-gen 0.5)
    (is (= 0.5 (pability/ability-get-skill-exp st u :arc-gen)))
    (is (= 1 (pability/ability-get-level st u)))
    (pability/ability-set-level! st u 3)
    (is (= 3 (pability/ability-get-level st u)))
    (is (= 0.0 (pability/ability-get-level-progress st u)))
    (pability/ability-set-level-progress! st u 0.25)
    (is (= 0.25 (pability/ability-get-level-progress st u)))

    ;; new-resource-data starts at full CP for level (config-driven)
    (is (= (double (cfg/max-cp-for-level 1)) (pability/res-get-cur-cp st u)))
    (is (= (double (cfg/max-cp-for-level 1)) (pability/res-get-max-cp st u)))
    (pability/res-set-cur-cp! st u 10.0)
    (is (= 10.0 (pability/res-get-cur-cp st u)))
    (is (= 0.0 (pability/res-get-cur-overload st u)))
    (is (= (double (cfg/max-overload-for-level 1)) (pability/res-get-max-overload st u)))
    (pability/res-set-cur-overload! st u 1.0)
    (is (= 1.0 (pability/res-get-cur-overload st u)))
    (is (true? (pability/res-is-overload-fine? st u)))
    (is (false? (pability/res-is-activated? st u)))
    (pability/res-set-activated! st u true)
    (is (true? (pability/res-is-activated? st u)))
    (is (= 0 (pability/res-get-until-recover st u)))
    (pability/res-set-until-recover! st u 5)
    (is (= 5 (pability/res-get-until-recover st u)))
    (is (= #{} (pability/res-get-interferences st u)))
    (pability/res-add-interference! st u :src-a)
    (is (= #{:src-a} (pability/res-get-interferences st u)))
    (pability/res-remove-interference! st u :src-a)
    (is (= #{} (pability/res-get-interferences st u)))

    (is (false? (pability/cd-is-in-cooldown? st u :ctrl :sub)))
    (pability/cd-set-cooldown! st u :ctrl :sub 3)
    (is (true? (pability/cd-is-in-cooldown? st u :ctrl :sub)))
    (is (= 3 (pability/cd-get-remaining st u :ctrl :sub)))
    (pability/cd-tick! st u)
    (is (= 2 (pability/cd-get-remaining st u :ctrl :sub)))

    (is (= 0 (pability/preset-get-active st u)))
    (pability/preset-set-active! st u 2)
    (is (= 2 (pability/preset-get-active st u)))
    (is (nil? (pability/preset-get-slot st u 0 0)))
    (pability/preset-set-slot! st u 0 0 [:cat :skill])
    (is (= [:cat :skill] (pability/preset-get-slot st u 0 0)))
    (is (map? (pability/preset-get-all st u)))))
