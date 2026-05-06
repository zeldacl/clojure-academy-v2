(ns cn.li.ac.ability.passive-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.state.player :as ps]))

(defn- reset-all! [f]
  (reset! ps/player-states {})
  (reset! @#'cn.li.ac.ability.registry.event/subscribers {})
  (reset! @#'cn.li.ac.ability.passive/registered-handlers #{})
  (f)
  (reset! ps/player-states {})
  (reset! @#'cn.li.ac.ability.registry.event/subscribers {})
  (reset! @#'cn.li.ac.ability.passive/registered-handlers #{}))

(use-fixtures :each reset-all!)

(deftest passive-calc-only-when-learned-test
  (is (true? (passive/register-passive-calc-handler!
              :passive-test-h1
              evt/CALC-MAX-CP
              :passive-skill
              (fn [v _] (+ v 25.0)))))
  (ps/set-player-state!
   "u1"
   (assoc (ps/fresh-state)
          :ability-data (ad/learn-skill (ad/new-ability-data) :passive-skill)))
  (is (= 125.0 (evt/fire-calc-event! evt/CALC-MAX-CP 100.0 {:uuid "u1"})))
  (is (= 100.0 (evt/fire-calc-event! evt/CALC-MAX-CP 100.0 {:uuid "u2"}))))

(deftest register-passive-idempotent-by-handler-id-test
  (is (true? (passive/register-passive-calc-handler!
              :once
              evt/CALC-MAX-OVERLOAD
              :s
              (fn [v _] (* v 2)))))
  ;; duplicate handler-id yields nil (not false)
  (is (nil? (passive/register-passive-calc-handler!
             :once
             evt/CALC-MAX-OVERLOAD
             :s
             (fn [v _] (* v 3))))))
