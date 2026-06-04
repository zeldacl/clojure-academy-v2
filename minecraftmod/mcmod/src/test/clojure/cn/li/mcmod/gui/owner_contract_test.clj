(ns cn.li.mcmod.gui.owner-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.owner-contract :as owner-contract]))

(def ^:private valid-client-owner
  {:logical-side :client
   :client-session-id :session-a
   :player-uuid "player-a"})

(def ^:private valid-server-owner
  {:logical-side :server
   :server-session-id [:server 1]
   :player-uuid "player-a"})

(deftest client-owner-contract-test
  (testing "valid client owner passes"
    (is (owner-contract/valid-client-owner? valid-client-owner))
    (is (= valid-client-owner
           (owner-contract/require-client-owner valid-client-owner))))
  (testing "missing client-session-id fails"
    (is (false? (owner-contract/valid-client-owner?
                  {:logical-side :client :player-uuid "player-a"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"client-owner contract violation"
                          (owner-contract/require-client-owner
                           {:logical-side :client :player-uuid "player-a"}))))
  (testing "wrong logical-side fails"
    (is (false? (owner-contract/valid-client-owner?
                  (assoc valid-client-owner :logical-side :server)))))
  (testing "missing player-uuid fails require-client-owner"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires :player-uuid"
                          (owner-contract/require-client-owner
                           {:logical-side :client :client-session-id :session-a})))))

(deftest server-owner-contract-test
  (testing "valid server owner passes"
    (is (owner-contract/valid-server-owner? valid-server-owner))
    (is (= valid-server-owner
           (owner-contract/require-server-owner valid-server-owner))))
  (testing "missing server-session-id fails"
    (is (false? (owner-contract/valid-server-owner?
                  {:logical-side :server :player-uuid "player-a"})))))

(deftest owner-or-contract-test
  (testing "accepts client or server owner"
    (is (owner-contract/valid-owner? valid-client-owner))
    (is (owner-contract/valid-owner? valid-server-owner)))
  (testing "mixed side keys fail"
    (is (false? (owner-contract/valid-owner?
                  {:logical-side :client
                   :server-session-id [:server 1]
                   :client-session-id :session-a
                   :player-uuid "player-a"})))))

(deftest message-envelope-contract-test
  (testing "valid envelope passes"
    (is (= {:msg-id "set-tab" :payload {:tab-index 1}}
           (owner-contract/require-message-envelope
            {:msg-id "set-tab" :payload {:tab-index 1}}))))
  (testing "missing payload fails with explain"
    (let [e (try
              (owner-contract/require-message-envelope {:msg-id "x"})
              (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :message-envelope (:contract (ex-data e))))
      (is (some? (:explain (ex-data e)))))))

(deftest sync-routing-contract-test
  (testing "container-id routing passes"
    (is (owner-contract/require-sync-routing {:container-id 17})))
  (testing "pos routing passes"
    (is (owner-contract/require-sync-routing {:pos-x 1 :pos-y 2 :pos-z 3})))
  (testing "empty routing fails"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"sync-routing contract violation"
                          (owner-contract/require-sync-routing {})))))
