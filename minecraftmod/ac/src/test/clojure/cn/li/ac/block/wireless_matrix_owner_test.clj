(ns cn.li.ac.block.wireless-matrix-owner-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.wireless-matrix.logic :as logic]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.world :as world]))

;; ============================================================================
;; owner-authorized?  — UUID primary + legacy name fallback
;; ============================================================================

(deftest owner-authorized-check
  ;; UUID primary path: when both stored UUID and player UUID are available
  (with-redefs [uuid/player-uuid (fn [_] "11111111-1111-1111-1111-111111111111")]
    (testing "UUID match grants access"
      (is (true? (logic/owner-authorized?
                  {:placer-uuid "11111111-1111-1111-1111-111111111111"
                   :placer-name "any"}
                  :player))))

    (testing "UUID mismatch denies access"
      (is (false? (logic/owner-authorized?
                   {:placer-uuid "00000000-0000-0000-0000-000000000000"
                    :placer-name "any"}
                   :player)))))

  ;; Legacy name fallback: when either stored UUID or player UUID is unavailable
  (with-redefs [entity/player-get-name (fn [_] "alice")
                uuid/player-uuid (fn [_] nil)]
    (testing "exact canonical owner (no UUID in state)"
      (is (true? (logic/owner-authorized?
                  {:placer-name "alice"}
                  :player))))

    (testing "legacy owner string (ServerPlayer toString format)"
      (is (true? (logic/owner-authorized?
                  {:placer-name "ServerPlayer['alice'/123, l='ServerLevel[test]', x=0.0, y=0.0, z=0.0]"}
                  :player))))

    (testing "blank owner remains permissive"
      (is (true? (logic/owner-authorized?
                  {:placer-name ""}
                  :player))))

    (testing "whitespace-only owner remains permissive"
      (is (true? (logic/owner-authorized?
                  {:placer-name "   "}
                  :player))))

    (testing "different owner is rejected"
      (is (false? (logic/owner-authorized?
                   {:placer-name "bob"}
                   :player))))

    (testing "owner comparison is case-sensitive"
      (is (false? (logic/owner-authorized?
                   {:placer-name "Alice"}
                   :player))))

    (testing "blank stored UUID falls through to name fallback, non-matching name → denied"
      (is (false? (logic/owner-authorized?
                   {:placer-uuid "" :placer-name "bob"}
                   :player))))))

;; ============================================================================
;; placer-name — state map → display string
;; ============================================================================

(deftest placer-name-from-state
  (is (= "Dev" (logic/placer-name {:placer-name "Dev"})))
  (is (= "" (logic/placer-name {})))
  (is (= "" (logic/placer-name nil))))

;; ============================================================================
;; placer-uuid — state map → UUID string or nil
;; ============================================================================

(deftest placer-uuid-from-state
  (is (= "abc-123" (logic/placer-uuid {:placer-uuid "abc-123"})))
  (is (nil? (logic/placer-uuid {})))
  (is (nil? (logic/placer-uuid nil))))

;; ============================================================================
;; handle-matrix-place — persists UUID + canonical name
;; ============================================================================

(deftest handle-matrix-place-persists-uuid-and-canonical-name
  (let [saved-state (atom nil)
        handler (logic/handle-matrix-place)]
    (with-redefs [world/get-tile-entity (fn [_ _] :be)
                  platform-be/get-custom-state (fn [_] {:foo 1})
                  platform-be/set-custom-state! (fn [_ state] (reset! saved-state state))
                  uuid/player-uuid (fn [_] "test-uuid-123")
                  entity/player-get-name (fn [_] "alice")]
      (handler :player :world :pos :wireless-matrix)
      (is (= "test-uuid-123" (:placer-uuid @saved-state)))
      (is (= "alice" (:placer-name @saved-state))))))
