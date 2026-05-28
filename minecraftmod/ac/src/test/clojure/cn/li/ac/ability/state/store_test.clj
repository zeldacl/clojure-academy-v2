(ns cn.li.ac.ability.state.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.state.store :as store]
            [cn.li.ac.ability.state.store-contract :as contract]
            [cn.li.ac.ability.service.snapshot :as snapshot]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(deftest ac-player-ability-store-roundtrip-test
  (let [st (store/ac-player-ability-store)
        u "player-uuid-1"]
    (is (nil? (contract/ability-get-category st u)))
    (contract/ability-set-category! st u :electromaster)
    (is (= :electromaster (contract/ability-get-category st u)))
    (is (false? (contract/ability-is-learned? st u :arc-gen)))
    (contract/ability-learn-skill! st u :arc-gen)
    (is (true? (contract/ability-is-learned? st u :arc-gen)))
    (is (= 0.0 (contract/ability-get-skill-exp st u :arc-gen)))
    (contract/ability-set-skill-exp! st u :arc-gen 0.5)
    (is (= 0.5 (contract/ability-get-skill-exp st u :arc-gen)))
    (is (= 1 (contract/ability-get-level st u)))
    (contract/ability-set-level! st u 3)
    (is (= 3 (contract/ability-get-level st u)))
    (is (= 0.0 (contract/ability-get-level-progress st u)))
    (contract/ability-set-level-progress! st u 0.25)
    (is (= 0.25 (contract/ability-get-level-progress st u)))

    ;; new-resource-data starts at full CP for level (config-driven)
    (is (= (double (cfg/max-cp-for-level 1)) (contract/res-get-cur-cp st u)))
    (is (= (double (cfg/max-cp-for-level 1)) (contract/res-get-max-cp st u)))
    (contract/res-set-cur-cp! st u 10.0)
    (is (= 10.0 (contract/res-get-cur-cp st u)))
    (is (= 0.0 (contract/res-get-cur-overload st u)))
    (is (= (double (cfg/max-overload-for-level 1)) (contract/res-get-max-overload st u)))
    (contract/res-set-cur-overload! st u 1.0)
    (is (= 1.0 (contract/res-get-cur-overload st u)))
    (is (true? (contract/res-is-overload-fine? st u)))
    (is (false? (contract/res-is-activated? st u)))
    (contract/res-set-activated! st u true)
    (is (true? (contract/res-is-activated? st u)))
    (is (= 0 (contract/res-get-until-recover st u)))
    (contract/res-set-until-recover! st u 5)
    (is (= 5 (contract/res-get-until-recover st u)))
    (is (= #{} (contract/res-get-interferences st u)))
    (contract/res-add-interference! st u :src-a)
    (is (= #{:src-a} (contract/res-get-interferences st u)))
    (contract/res-remove-interference! st u :src-a)
    (is (= #{} (contract/res-get-interferences st u)))

    (is (false? (contract/cd-is-in-cooldown? st u :ctrl :sub)))
    (contract/cd-set-cooldown! st u :ctrl :sub 3)
    (is (true? (contract/cd-is-in-cooldown? st u :ctrl :sub)))
    (is (= 3 (contract/cd-get-remaining st u :ctrl :sub)))
    (contract/cd-tick! st u)
    (is (= 2 (contract/cd-get-remaining st u :ctrl :sub)))

    (is (= 0 (contract/preset-get-active st u)))
    (contract/preset-set-active! st u 2)
    (is (= 2 (contract/preset-get-active st u)))
    (is (nil? (contract/preset-get-slot st u 0 0)))
    (contract/preset-set-slot! st u 0 0 [:electromaster :arc-gen])
    (is (= [:electromaster :arc-gen] (contract/preset-get-slot st u 0 0)))
    (contract/ability-set-category! st u :meltdowner)
    (is (= :meltdowner (contract/ability-get-category st u)))
    (is (nil? (contract/preset-get-slot st u 0 0))
      "category changes should clear stale preset slots")
    (is (map? (contract/preset-get-all st u)))

    (contract/with-player-ability-store st
      (is (= {:category-id :meltdowner
            :level 1
            :level-progress 0.0}
             (snapshot/ability-data->map u)))
      (is (= {:cur-cp 10.0
              :max-cp (double (cfg/max-cp-for-level 1))
              :cur-overload 1.0
              :max-overload (double (cfg/max-overload-for-level 1))
              :activated true
              :until-recover 5}
             (snapshot/resource-data->map u))))))
