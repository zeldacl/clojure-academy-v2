(ns cn.li.ac.ability.state.player-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.state.player :as ps]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(deftest fresh-state-shape-test
  (let [s (ps/fresh-state)]
    (is (map? (:ability-data s)))
    (is (map? (:resource-data s)))
    (is (map? (:cooldown-data s)))
    (is (map? (:preset-data s)))
    (is (map? (:develop-data s)))
    (is (map? (:terminal-data s)))
    (is (false? (:dirty? s)))))

(deftest get-or-create-player-state-test
  (let [a (ps/get-or-create-player-state! "u1")
        b (ps/get-or-create-player-state! "u1")]
    (is (identical? a b))))

(deftest update-ability-data-marks-dirty-test
  (ps/get-or-create-player-state! "u2")
  (is (false? (ps/dirty? "u2")))
  (ps/update-ability-data! "u2" assoc :category-id :x)
  (is (true? (ps/dirty? "u2")))
  (ps/mark-clean! "u2")
  (is (false? (ps/dirty? "u2"))))

(deftest server-tick-player-smoke-test
  (ps/get-or-create-player-state! "u3")
  (let [r (ps/server-tick-player! "u3" nil)]
    (is (map? r))
    (is (vector? (:events r)))))

(deftest remove-player-state-test
  (ps/set-player-state! "u4" (ps/fresh-state))
  (is (some? (ps/get-player-state "u4")))
  (ps/remove-player-state! "u4")
  (is (nil? (ps/get-player-state "u4"))))
