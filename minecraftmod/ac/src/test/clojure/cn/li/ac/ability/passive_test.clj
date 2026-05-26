(ns cn.li.ac.ability.passive-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.test.support.player-state :as ps-fix]))

(defn- reset-all! [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (ps/reset-player-states-for-test!)
      (evt/reset-ability-event-subscribers-for-test!)
      (passive/reset-passive-handler-registry-for-test!)
      (try
        (f)
        (finally
          (ps/reset-player-states-for-test!)
          (evt/reset-ability-event-subscribers-for-test!)
          (passive/reset-passive-handler-registry-for-test!))))))

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

(deftest passive-handler-registry-freeze-policy-test
  (passive/freeze-passive-handler-registry!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Passive handler registry is frozen"
                        (passive/register-passive-calc-handler!
                         :new-passive
                         evt/CALC-MAX-CP
                         :s
                         (fn [v _] v)))))
