(ns cn.li.ac.content.ability.meltdowner.damage-helper-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.server.damage.runtime :as rt]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as dh]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(deftest process-damage-without-mark-is-unchanged-test
  (testing "no rad mark leaves damage unchanged (other handlers may still run)"
    (reset! ps/player-states {})
    (let [victim (str (random-uuid))
          out (rt/process-damage! victim nil 42.0 :test-source)]
      (is (= 42.0 (double out))))
    (reset! ps/player-states {})))

(deftest marked-target-gets-multiplied-damage-test
  (testing "mark-target! installs active mark rate until expiry"
    (reset! ps/player-states {})
    (let [attacker "atk-1"
          victim (str (random-uuid))
          ad (-> (ad/new-ability-data) (ad/learn-skill :rad-intensify))]
      (try
        (ps/set-player-state! attacker {:ability-data ad})
        (with-redefs [rad/rate (fn [_sid] 1.75)]
          (dh/mark-target! attacker victim)
          (let [scaled (rt/process-damage! victim nil 80.0 :magic)]
            (is (= (* 80.0 1.75) (double scaled)))))
        (finally
          (reset! ps/player-states {}))))))
