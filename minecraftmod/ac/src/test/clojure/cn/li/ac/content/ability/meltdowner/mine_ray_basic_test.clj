(ns cn.li.ac.content.ability.meltdowner.mine-ray-basic-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.content.ability.meltdowner.mine-ray-basic :as basic]
            [cn.li.ac.content.ability.meltdowner.mine-rays-base :as base]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]))

(deftest mine-ray-basic-tick-delegates-with-cfg-test
  (let [calls* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.0)
                  skill-config/lerp-double (fn [_skill-id field-id _exp]
                                             (case field-id
                                               :targeting.range 8.0
                                               :mining.break-speed 0.15
                                               0.0))
                  skill-config/tunable-double (fn [_skill-id field-id]
                                                (case field-id
                                                  :progression.exp-block 0.001
                                                  0.0))
                  base/mining-ray-tick! (fn [cfg evt]
                                          (swap! calls* conj [cfg evt])
                                          nil)]
      (basic/mine-ray-basic-tick! {:player-id "p1" :ctx-id "ctx-1"}))
    (is (= 1 (count @calls*)))
    (is (= {:range 8.0 :break-speed 0.15 :skill-id :mine-ray-basic :exp-block 0.001 :lucky? false}
          (ffirst @calls*)))))

(deftest mine-ray-basic-up-and-abort-delegate-reset-test
  (let [calls* (atom [])]
    (with-redefs [base/mining-ray-up! (fn [cfg evt]
                                        (swap! calls* conj [:up cfg evt])
                                        nil)
                  base/mining-ray-abort! (fn [cfg evt]
                                           (swap! calls* conj [:abort cfg evt])
                                           nil)]
      (basic/mine-ray-basic-up! {:ctx-id "ctx-2"})
      (basic/mine-ray-basic-abort! {:ctx-id "ctx-2"}))
    (is (= [:up :abort] (mapv first @calls*)))))
