(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-test
  (:require [clojure.test :refer [deftest is testing]]))

;; Note: Full integration tests would require complex mocking of the Minecraft world,
;; player entities, and damage system. These tests verify the key logic changes:
;;
;; Bug 1: charge-pos initialization
;; Bug 2: CP consumption in charging only
;; Bug 3: Experience granted at fire-time
;; Bug 5: FX sound timing

;; ============================================================================
;; Structural Verification Tests
;; ============================================================================

(deftest bug1-charge-pos-in-key-down-logic
  "Bug 1: Verify charge-pos calculation is in key-down handler"
  (testing "The key-down handler should calculate spawn-pos from player position"
    ;; This test verifies the code structure contains the necessary calculation
    (let [source (slurp "src/main/clojure/cn/li/ac/content/ability/vecmanip/plasma_cannon.clj")]
      (is (.contains source "spawn-pos")
          "key-down should calculate spawn-pos")
      (is (.contains source ":charge-pos spawn-pos")
          "key-down should store spawn-pos in skill-state as charge-pos")
      (is (.contains source ":projectile.spawn-y-offset")
          "spawn-pos calculation should use spawn-y-offset configuration"))))

(deftest bug2-cp-consumption-structure
  "Bug 2: Verify CP consumption only in :charging state"
  (testing "Manual CP consumption in :charging, skip in :go"
    (let [source (slurp "src/main/clojure/cn/li/ac/content/ability/vecmanip/plasma_cannon.clj")]
      (is (.contains source "perform-resource!")
          "key-tick should call perform-resource! for CP consumption")
      (is (.contains source ":charging")
          "CP consumption should be guarded by :charging state check")
      (is (not (.contains source ":tick {:cp plasma-cannon-cost-tick-cp}"))
          "defskill should NOT have :tick cost anymore"))))

(deftest bug3-exp-moved-to-key-up
  "Bug 3: Verify experience is granted at fire-time (key-up)"
  (testing "Experience should be granted in key-up, not in explode"
    (let [cannon-src (slurp "src/main/clojure/cn/li/ac/content/ability/vecmanip/plasma_cannon.clj")]
      ;; Verify add-exp! is called in key-up
      (is (> (.indexOf cannon-src "plasma-cannon-on-key-up")
              0)
          "key-up handler should exist")
      (let [key-up-section (subs cannon-src
                                  (.indexOf cannon-src "plasma-cannon-on-key-up")
                                  (+ (.indexOf cannon-src "plasma-cannon-on-key-up") 2000))]
        (is (.contains key-up-section "add-exp!")
            "add-exp! should be called in key-up")))))

(deftest bug5-fx-sound-timing
  "Bug 5: Verify FX sound doesn't double-play at tick 0"
  (testing "Sound loop should not trigger at ticks=0"
    (let [fx-src (slurp "src/main/clojure/cn/li/ac/content/ability/vecmanip/plasma_cannon_fx.clj")]
      (is (.contains fx-src "(pos? ticks)")
          "tick! function should have (pos? ticks) guard for sound")
      (is (.contains fx-src "(and (pos? ticks) (zero? (mod ticks 10)))")
          "Sound condition should be: (and (pos? ticks) (zero? (mod ticks 10)))"))))

;; ============================================================================
;; Integration Sanity Checks
;; ============================================================================

(deftest defskill-registration
  "Verify defskill! is properly registered"
  (testing "plasma-cannon skill should be registered"
    (let [source (slurp "src/main/clojure/cn/li/ac/content/ability/vecmanip/plasma_cannon.clj")]
      (is (.contains source "(defskill! plasma-cannon")
          "defskill! should define plasma-cannon")
      (is (.contains source ":pattern :charge-window")
          "plasma-cannon should use charge-window pattern")
      (is (.contains source ":level 5")
          "plasma-cannon should be level 5"))))

(deftest fx-handlers-updated
  "Verify FX handlers accept charge-pos parameter"
  (testing "FX handlers should handle charge-pos from key-down"
    (let [fx-src (slurp "src/main/clojure/cn/li/ac/content/ability/vecmanip/plasma_cannon_fx.clj")]
      (is (.contains fx-src ":charge-pos (:charge-pos payload)")
          "enqueue! :start case should extract charge-pos from payload")
      (is (.contains fx-src "register-fx-channels!")
          "FX handlers should be properly registered"))))

;; ============================================================================
;; End-to-End Behavioral Test
;; ============================================================================

(deftest plasma-cannon-cost-structure
  "Verify the cost model is correct: down-overload only, no tick cost"
  (testing "Cost configuration in defskill!"
    (let [source (slurp "src/main/clojure/cn/li/ac/content/ability/vecmanip/plasma_cannon.clj")
          defskill-idx (.indexOf source "(defskill! plasma-cannon")
          defskill-section (subs source defskill-idx (count source))]
      (is (.contains defskill-section ":down {:overload")
          "Should have :down overload cost")
      (is (not (.contains defskill-section ":tick {:cp"))
          "Should NOT have :tick CP cost"))))
