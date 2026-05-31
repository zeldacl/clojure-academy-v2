(ns cn.li.ac.ability.service.command-runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.reducer :as reducer]
            [cn.li.ac.ability.effects.interpreter :as interpreter]))

(def ^:private baseline-state
  {:ability-data {}
   :resource-data {}
   :cooldown-data {}
   :preset-data {}
   :context-registry {}
   :dirty? false})

(deftest run-command-in-session-injects-session-id-test
  (testing "explicit session id is propagated into reducer command payload"
    (let [captured-command (atom nil)]
      (with-redefs [store/get-or-create-player-state! (fn [_session-id _uuid] baseline-state)
                    store/set-player-state!* (fn [_session-id _uuid _state] nil)
                    store/mark-player-dirty! (fn [_session-id _uuid] nil)
                    reducer/apply-command (fn [state command]
                                            (reset! captured-command command)
                                            {:state state :events [] :effects []})
                    interpreter/execute-reducer-result! (fn [_result] nil)]
        (command-rt/run-command-in-session! "session-A" "player-A"
                                            {:command :level-up})
        (is (= "session-A" (:session-id @captured-command)))
        (is (= "player-A" (:player-uuid @captured-command)))
        (is (= :level-up (:command @captured-command)))
        (is (string? (:command-id @captured-command)))
        (is (not (str/blank? (:command-id @captured-command))))))))

(deftest run-command-in-session-preserves-command-id-test
  (testing "existing command-id is preserved during normalization"
    (let [captured-command (atom nil)]
      (with-redefs [store/get-or-create-player-state! (fn [_session-id _uuid] baseline-state)
                    store/set-player-state!* (fn [_session-id _uuid _state] nil)
                    store/mark-player-dirty! (fn [_session-id _uuid] nil)
                    reducer/apply-command (fn [state command]
                                            (reset! captured-command command)
                                            {:state state :events [] :effects []})
                    interpreter/execute-reducer-result! (fn [_result] nil)]
        (command-rt/run-command-in-session! "session-A" "player-A"
                                            {:command :level-up
                                             :command-id "fixed-command-id"})
        (is (= "fixed-command-id" (:command-id @captured-command)))))))

(deftest run-command-in-session-idempotent-replay-test
  (testing "idempotent mode replays cached result and skips reducer/effects"
    (command-rt/reset-command-traces-for-test!)
    (let [apply-count (atom 0)
          effect-count (atom 0)
          command {:command :level-up
                   :command-id "idempotent-command-1"}
          opts {:idempotent? true}]
          (with-redefs [store/get-or-create-player-state! (fn [_session-id _uuid] baseline-state)
                    store/set-player-state!* (fn [_session-id _uuid _state] nil)
                    store/mark-player-dirty! (fn [_session-id _uuid] nil)
                    reducer/apply-command (fn [state _normalized-command]
                                            (swap! apply-count inc)
                                            {:state state
                                             :events [{:event/type :ability/test-event}]
                                             :effects [{:effect/type :persist-state}]})
                    interpreter/execute-reducer-result! (fn [_result]
                                                          (swap! effect-count inc)
                                                          nil)]
        (let [first-result (command-rt/run-command-in-session! "session-A" "player-A" command opts)
              second-result (command-rt/run-command-in-session! "session-A" "player-A" command opts)]
          (is (= 1 @apply-count))
          (is (= 1 @effect-count))
          (is (nil? (:idempotent-replay? first-result)))
          (is (true? (:idempotent-replay? second-result))))))))

(deftest run-command-in-session-records-trace-test
  (testing "command trace snapshot contains completed command entry"
    (command-rt/reset-command-traces-for-test!)
    (with-redefs [store/get-or-create-player-state! (fn [_session-id _uuid] baseline-state)
                  store/set-player-state!* (fn [_session-id _uuid _state] nil)
                  store/mark-player-dirty! (fn [_session-id _uuid] nil)
                  reducer/apply-command (fn [state _normalized-command]
                                          {:state state :events [] :effects []})
                  interpreter/execute-reducer-result! (fn [_result] nil)]
      (command-rt/run-command-in-session! "session-Z" "player-Z"
                                          {:command :level-up
                                           :command-id "trace-check-1"})
      (is (contains? (command-rt/command-traces-snapshot)
                     ["session-Z" "player-Z" "trace-check-1"])))))
