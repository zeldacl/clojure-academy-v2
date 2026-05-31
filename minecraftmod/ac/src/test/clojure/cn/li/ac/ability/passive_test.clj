(ns cn.li.ac.ability.passive-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.registry.event :as evt]            [cn.li.ac.test.support.player-state :as ps-fix]))

(defn- reset-all! [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (store/reset-store!)
      (evt/reset-ability-event-subscribers-for-test!)
      (passive/reset-passive-handler-registry-for-test!)
      (try
        (f)
        (finally
          (store/reset-store!)
          (evt/reset-ability-event-subscribers-for-test!)
          (passive/reset-passive-handler-registry-for-test!))))))

(use-fixtures :each reset-all!)

(deftest passive-calc-only-when-learned-test
  (is (true? (passive/register-passive-calc-handler!
              :passive-test-h1
              evt/CALC-MAX-CP
              :passive-skill
              (fn [v _] (+ v 25.0)))))
    (store/set-player-state!* ps-fix/test-session-id
          "u1"
          (assoc (store/fresh-player-state)
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

(deftest passive-handler-runtime-isolation-test
  (let [runtime-a (passive/create-passive-handler-runtime)
        runtime-b (passive/create-passive-handler-runtime)]
    (passive/call-with-passive-handler-runtime
      runtime-a
      (fn []
        (is (true? (passive/register-passive-calc-handler!
                    :iso-a
                    evt/CALC-MAX-CP
                    :s
                    (fn [v _] v))))
        (is (= #{:iso-a}
               (passive/passive-handler-registry-snapshot)))))
    (passive/call-with-passive-handler-runtime
      runtime-b
      (fn []
        (is (empty? (passive/passive-handler-registry-snapshot)))
        (is (true? (passive/register-passive-calc-handler!
                    :iso-b
                    evt/CALC-MAX-CP
                    :s
                    (fn [v _] v))))
        (is (= #{:iso-b}
               (passive/passive-handler-registry-snapshot)))))
    (passive/call-with-passive-handler-runtime
      runtime-a
      (fn []
        (is (= #{:iso-a}
               (passive/passive-handler-registry-snapshot)))))))


