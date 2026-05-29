(ns cn.li.ac.content.ability.meltdowner.damage-helper-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.server.damage.runtime :as rt]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as dh]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(defn- with-fresh-marks-runtime [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (dh/call-with-damage-helper-runtime (dh/create-damage-helper-runtime)
        (fn []
          (ps/reset-player-states-for-test!)
          (try
            (f)
            (finally
              (dh/reset-marks-for-test!)
              (ps/reset-player-states-for-test!))))))))

(use-fixtures :each with-fresh-marks-runtime)

(defn- learned-rad-intensify-data
  []
  (-> (ad/new-ability-data) (ad/learn-skill :rad-intensify)))

(defn- learn-rad-intensify!
  [player-id]
  (ps/set-player-state! player-id {:ability-data (learned-rad-intensify-data)}))

(deftest process-damage-without-mark-is-unchanged-test
  (testing "no rad mark leaves damage unchanged (other handlers may still run)"
    (dh/ensure-damage-handler!)
    (let [victim (str (random-uuid))
          out (rt/process-damage! victim "attacker-1" 42.0 :test-source)]
      (is (= 42.0 (double out))))))

(deftest marked-target-gets-multiplied-damage-test
  (testing "mark-target! installs source-target active mark rate until expiry"
    (let [attacker "atk-1"
          victim (str (random-uuid))
          _ (dh/ensure-damage-handler!)]
      (learn-rad-intensify! attacker)
      (with-redefs [rad/rate (fn [_sid] 1.75)]
        (dh/mark-target! attacker victim)
        (is (= 1.75 (:rate (get (dh/marks-snapshot) [attacker victim]))))
        (let [scaled (rt/process-damage! victim attacker 80.0 :magic)]
          (is (= (* 80.0 1.75) (double scaled))))))))

(deftest marked-target-requires-matching-attacker-test
  (testing "a mark created by one attacker does not amplify another attacker's damage"
    (let [attacker "atk-owner"
          other-attacker "atk-other"
          victim (str (random-uuid))]
      (dh/ensure-damage-handler!)
      (learn-rad-intensify! attacker)
      (with-redefs [rad/rate (fn [_sid] 1.75)]
        (dh/mark-target! attacker victim)
        (is (= 80.0 (double (rt/process-damage! victim other-attacker 80.0 :magic))))
        (is (= (* 80.0 1.75)
               (double (rt/process-damage! victim attacker 80.0 :magic))))))))

(deftest multiple-attackers-can-mark-same-target-independently-test
  (testing "source-target keys prevent same-victim marks from overwriting each other"
    (let [attacker-a "atk-a"
          attacker-b "atk-b"
          victim (str (random-uuid))]
      (dh/ensure-damage-handler!)
      (learn-rad-intensify! attacker-a)
      (learn-rad-intensify! attacker-b)
      (with-redefs [rad/rate (fn [sid]
                               (case sid
                                 "atk-a" 1.5
                                 "atk-b" 2.0))]
        (dh/mark-target! attacker-a victim)
        (dh/mark-target! attacker-b victim)
        (is (= #{[attacker-a victim] [attacker-b victim]}
               (set (keys (dh/marks-snapshot)))))
        (is (= 15.0 (double (rt/process-damage! victim attacker-a 10.0 :magic))))
        (is (= 20.0 (double (rt/process-damage! victim attacker-b 10.0 :magic))))))))

(deftest expired-mark-cleanup-is-source-target-scoped-test
  (testing "expired source-target cleanup does not remove another attacker's active mark"
    (let [attacker-a "atk-a"
          attacker-b "atk-b"
          victim "victim-1"]
      (dh/ensure-damage-handler!)
      (dh/reset-marks-for-test!
        {[attacker-a victim] {:source-player-id attacker-a
                              :target-id victim
                              :expire-at 0
                              :rate 3.0}
         [attacker-b victim] {:source-player-id attacker-b
                              :target-id victim
                              :expire-at Long/MAX_VALUE
                              :rate 2.0}})
      (is (= 10.0 (double (rt/process-damage! victim attacker-a 10.0 :magic))))
      (is (nil? (get (dh/marks-snapshot) [attacker-a victim])))
      (is (= 2.0 (:rate (get (dh/marks-snapshot) [attacker-b victim]))))
      (dh/clear-expired-marks!)
      (is (= #{[attacker-b victim]} (set (keys (dh/marks-snapshot))))))))

(deftest marks-snapshot-reset-and-clear-test
  (let [source-a "atk-a"
        source-b "atk-b"
        target "victim-1"
        other-target "victim-2"]
    (dh/reset-marks-for-test!
      {[source-a target] {:source-player-id source-a :target-id target :expire-at Long/MAX_VALUE :rate 2.0}
       [source-b target] {:source-player-id source-b :target-id target :expire-at Long/MAX_VALUE :rate 3.0}
       [source-a other-target] {:source-player-id source-a :target-id other-target :expire-at Long/MAX_VALUE :rate 4.0}})
    (is (= 2.0 (:rate (get (dh/marks-snapshot) [source-a target]))))
    (dh/clear-mark! source-a target)
    (is (nil? (get (dh/marks-snapshot) [source-a target])))
    (is (contains? (dh/marks-snapshot) [source-b target]))
    (is (contains? (dh/marks-snapshot) [source-a other-target]))
    (dh/clear-target-marks! target)
    (is (= #{[source-a other-target]} (set (keys (dh/marks-snapshot)))))
    (dh/clear-source-marks! source-a)
    (is (empty? (dh/marks-snapshot)))))

(deftest damage-helper-runtime-isolation-test
  (let [runtime-a (dh/create-damage-helper-runtime)
        runtime-b (dh/create-damage-helper-runtime)
        source "atk-a"
        target "victim-1"
        key [source target]]
    (dh/call-with-damage-helper-runtime runtime-a
      (fn []
        (dh/reset-marks-for-test!
          {key {:source-player-id source
                :target-id target
                :expire-at Long/MAX_VALUE
                :rate 2.0}})))
    (dh/call-with-damage-helper-runtime runtime-b
      (fn []
        (dh/reset-marks-for-test!
          {key {:source-player-id source
                :target-id target
                :expire-at Long/MAX_VALUE
                :rate 3.0}})
        (is (= 3.0 (:rate (get (dh/marks-snapshot) key))))
        (dh/clear-mark! source target)
        (is (empty? (dh/marks-snapshot)))))
    (dh/call-with-damage-helper-runtime runtime-a
      (fn []
        (is (= 2.0 (:rate (get (dh/marks-snapshot) key))))))))

(deftest damage-helper-throws-without-binding-test
  (let [source "atk-fallback"
        target "victim-fallback"
        key [source target]]
    (binding [dh/*damage-helper-runtime* nil]
  (is (thrown-with-msg?
    clojure.lang.ExceptionInfo
    #"Damage helper runtime is not bound"
    (dh/reset-marks-for-test!
      {key {:source-player-id source
        :target-id target
        :expire-at Long/MAX_VALUE
        :rate 1.25}}))))))
