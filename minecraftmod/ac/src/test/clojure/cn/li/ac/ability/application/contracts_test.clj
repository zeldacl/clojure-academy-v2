(ns cn.li.ac.ability.application.contracts-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.application.contracts :as contracts]
            [cn.li.ac.ability.service.command-runtime :as command-rt]))

(deftest command-contract-basic-shape-test
  (testing "command map requires :command keyword"
    (is (true? (contracts/command? {:command :learn-skill})))
    (is (false? (contracts/command? {:command "learn"})))
    (is (false? (contracts/command? {:skill-id :foo}))))

  (testing "trim-command-meta removes trace-only keys"
    (is (= {:command :learn-skill :skill-id :foo}
           (contracts/trim-command-meta {:command :learn-skill
                                         :skill-id :foo
                                         :trace-id "abc"
                                         :trace/span :x
                                         :debug/source :test})))))

(deftest reducer-result-contract-shape-test
  (is (true? (contracts/reducer-result?
              {:state {}
               :events [{:event/type :ability/foo}]
               :effects [{:effect/type :persist-state}]})))
  (is (false? (contracts/reducer-result?
               {:state {}
                :events [{:event/type "bad"}]
                :effects []})))
  (is (false? (contracts/reducer-result?
               {:state {}
                :events []
                :effects [{:effect/type "bad"}]}))))

(deftest command-runtime-rejects-malformed-command-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"command invalid"
  (command-rt/run-command-in-session! "session-test" "p1" {:skill-id :foo}))))
