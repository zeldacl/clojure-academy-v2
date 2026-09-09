(ns cn.li.ac.ability.client.input-state-machine-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.client.input-state-machine :as sm]))

(def ^:private delegate
  {:skill-id :arc-gen
   :on-key-down identity
   :on-key-tick identity
   :on-key-up identity
   :on-key-abort identity})

(deftest skill-key-blocks-press-when-unbound
  (testing "rising-edge with no delegate is blocked, not silent nil"
    (let [key-state sm/default-key-state
          player-state {:resource-data {:activated true :overload-fine true}
                        :cooldown-data {}}
          event (sm/compute-skill-key-event key-state player-state 0 true nil)]
      (is (= :blocked (:transition event)))
      (is (= :unbound (:reason event)))
      (is (= 0 (:key-idx event)))))
  (testing "hold ticks with no delegate stay quiet"
    (let [key-state (sm/next-skill-key-state sm/default-key-state 0 true)
          player-state {:resource-data {:activated true :overload-fine true}
                        :cooldown-data {}}]
      (is (nil? (sm/compute-skill-key-event key-state player-state 0 true nil))))))

(deftest skill-key-ignores-press-when-deactivated
  (testing "ability mode off blocks press with a visible reason (not silent nil)"
    (let [key-state (sm/next-skill-key-state sm/default-key-state 0 false)
          player-state {:resource-data {:activated false :overload-fine true}
                        :cooldown-data {}}
          event (sm/compute-skill-key-event key-state player-state 0 true delegate)]
      (is (= :blocked (:transition event)))
      (is (= :inactive (:reason event)))
      (is (nil? (sm/compute-skill-key-event
                 (sm/next-skill-key-state key-state 0 true)
                 player-state 0 true delegate)))))
  (testing "release still forwards while deactivated so holds can clean up"
    (let [key-state (sm/next-skill-key-state sm/default-key-state 0 true)
          player-state {:resource-data {:activated false :overload-fine true}
                        :cooldown-data {}}
          event (sm/compute-skill-key-event key-state player-state 0 false delegate)]
      (is (= :release (:transition event)))
      (is (= delegate (:delegate event))))))

(deftest skill-key-presses-when-activated
  (let [key-state sm/default-key-state
        player-state {:resource-data {:activated true :overload-fine true}
                      :cooldown-data {}}
        event (sm/compute-skill-key-event key-state player-state 0 true delegate)]
    (is (= :press (:transition event)))
    (is (= delegate (:delegate event)))))

(deftest skill-key-blocks-press-when-unusable-or-cooling
  (testing "overload recovery blocks press without aborting"
    (let [key-state sm/default-key-state
          player-state {:resource-data {:activated true :overload-fine false}
                        :cooldown-data {}}
          event (sm/compute-skill-key-event key-state player-state 0 true delegate)]
      (is (= :blocked (:transition event)))
      (is (= :unusable (:reason event)))))
  (testing "cooldown blocks press without aborting"
    (let [key-state sm/default-key-state
          player-state {:resource-data {:activated true :overload-fine true}
                        :cooldown-data {[:arc-gen :main] {:ticks 20 :max 20}}}
          event (sm/compute-skill-key-event key-state player-state 0 true delegate)]
      (is (= :blocked (:transition event)))
      (is (= :cooldown (:reason event)))))
  (testing "tick while unusable still aborts a held skill"
    (let [key-state (sm/next-skill-key-state sm/default-key-state 0 true)
          player-state {:resource-data {:activated true :overload-fine false}
                        :cooldown-data {}}
          event (sm/compute-skill-key-event key-state player-state 0 true delegate)]
      (is (= :abort (:transition event))))))
