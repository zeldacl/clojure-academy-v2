(ns cn.li.ac.content.ability.meltdowner.damage-helper-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.server.damage.runtime :as rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as dh]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(defn- with-fresh-marks-runtime [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (store/reset-store!)
      (try
        (f)
        (finally
          (dh/reset-marks-for-test!)
          (store/reset-store!))))))

(use-fixtures :each with-fresh-marks-runtime)

(defn- learned-rad-intensify-data []
  (-> (ad/new-ability-data) (ad/learn-skill :rad-intensify)))

(defn- learn-rad-intensify! [player-id]
  (ps-fix/seed-player-state! player-id {:ability-data (learned-rad-intensify-data)}))

(deftest process-damage-without-mark-is-unchanged-test
  (testing "no rad mark leaves damage unchanged (other handlers may still run)"
    (dh/ensure-damage-handler!)
    (let [victim (str (random-uuid))
          out (rt/process-damage! victim "attacker-1" 42.0 :test-source)]
      (is (= 42.0 (double out))))))

(deftest marked-target-gets-multiplied-damage-test
  (testing "mark-target! installs target mark rate until ticks expire"
    (let [attacker "atk-1"
          victim (str (random-uuid))]
      (dh/ensure-damage-handler!)
      (learn-rad-intensify! attacker)
      (with-redefs [rad/rate (fn [_sid] 1.75)
                    rad/mark-duration-ticks (fn [] 120)]
        (dh/mark-target! attacker victim)
        (is (= 1.75 (:rate (get (dh/marks-snapshot) victim))))
        (is (= 140.0 (double (rt/process-damage! victim attacker 80.0 :magic))))))))

(deftest marked-target-does-not-require-matching-attacker-test
  (testing "target mark amplifies damage from any attacker while active"
    (let [attacker-a "atk-owner"
          attacker-b "atk-other"
          victim (str (random-uuid))]
      (dh/ensure-damage-handler!)
      (learn-rad-intensify! attacker-a)
      (with-redefs [rad/rate (fn [_sid] 1.75)
                    rad/mark-duration-ticks (fn [] 100)]
        (dh/mark-target! attacker-a victim)
        (is (= 140.0 (double (rt/process-damage! victim attacker-b 80.0 :magic))))
        (is (= 140.0 (double (rt/process-damage! victim attacker-a 80.0 :magic))))))))

(deftest marked-target-applies-without-attacker-test
  (testing "nil attacker still amplifies because mark belongs to target"
    (let [attacker "atk-owner"
          victim (str (random-uuid))]
      (dh/ensure-damage-handler!)
      (learn-rad-intensify! attacker)
      (with-redefs [rad/rate (fn [_sid] 1.5)
                    rad/mark-duration-ticks (fn [] 100)]
        (dh/mark-target! attacker victim)
        (is (= 15.0 (double (rt/process-damage! victim nil 10.0 :magic))))))))

(deftest mark-refresh-overwrites-rate-and-ticks-test
  (let [attacker-a "atk-a"
        attacker-b "atk-b"
        victim "victim-1"]
    (dh/ensure-damage-handler!)
    (learn-rad-intensify! attacker-a)
    (learn-rad-intensify! attacker-b)
    (with-redefs [rad/rate (fn [sid]
                             (case sid
                               "atk-a" 1.5
                               "atk-b" 2.0
                               1.0))
                  rad/mark-duration-ticks (fn [] 100)]
      (dh/mark-target! attacker-a victim)
      (is (= 100 (:ticks-left (get (dh/marks-snapshot) victim))))
      (dh/tick-marks!)
      (is (= 99 (:ticks-left (get (dh/marks-snapshot) victim))))
      (dh/mark-target! attacker-b victim)
      (is (= 100 (:ticks-left (get (dh/marks-snapshot) victim))))
      (is (= 2.0 (:rate (get (dh/marks-snapshot) victim)))))))

(deftest expired-mark-cleanup-by-ticks-test
  (let [victim-a "victim-a"
        victim-b "victim-b"]
    (dh/ensure-damage-handler!)
    (dh/reset-marks-for-test!
      {victim-a {:source-player-id "atk-a"
                 :target-id victim-a
                 :ticks-left 0
                 :rate 3.0}
       victim-b {:source-player-id "atk-b"
                 :target-id victim-b
                 :ticks-left 2
                 :rate 2.0}})
    (is (= 10.0 (double (rt/process-damage! victim-a "atk-a" 10.0 :magic))))
    (is (nil? (get (dh/marks-snapshot) victim-a)))
    (is (= 20.0 (double (rt/process-damage! victim-b "atk-z" 10.0 :magic))))
    (dh/tick-marks!)
    (is (= 1 (:ticks-left (get (dh/marks-snapshot) victim-b))))
    (dh/tick-marks!)
    (is (nil? (get (dh/marks-snapshot) victim-b)))))

(deftest marks-snapshot-reset-and-clear-test
  (let [target-a "victim-1"
        target-b "victim-2"]
    (dh/reset-marks-for-test!
      {target-a {:source-player-id "atk-a" :target-id target-a :ticks-left 5 :rate 2.0}
       target-b {:source-player-id "atk-a" :target-id target-b :ticks-left 5 :rate 4.0}})
    (is (= 2.0 (:rate (get (dh/marks-snapshot) target-a))))
    (dh/clear-target-mark! target-a)
    (is (nil? (get (dh/marks-snapshot) target-a)))
    (is (contains? (dh/marks-snapshot) target-b))
    (dh/clear-source-marks! "atk-a")
    (is (empty? (dh/marks-snapshot)))))

(deftest damage-helper-marks-are-per-attacker-state-test
  (let [target-a "victim-a"
        target-b "victim-b"]
    (dh/reset-marks-for-test!
      {target-a {:source-player-id "atk-a"
                 :target-id target-a
                 :ticks-left 2
                 :rate 2.0}
       target-b {:source-player-id "atk-b"
                 :target-id target-b
                 :ticks-left 4
                 :rate 3.0}})
    (dh/tick-marks!)
    (is (= 1 (:ticks-left (get (dh/marks-snapshot) target-a))))
    (is (= 3 (:ticks-left (get (dh/marks-snapshot) target-b))))))
