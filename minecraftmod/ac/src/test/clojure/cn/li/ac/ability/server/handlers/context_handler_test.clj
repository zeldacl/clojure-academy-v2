(ns cn.li.ac.ability.server.handlers.context-handler-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.handlers.context-handler :as context-handler]
            [cn.li.ac.ability.server.handlers.input-handler :as input-handler]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.server.service.context-runtime :as ctx-rt]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.test.support.contexts :as test-contexts]))

(use-fixtures :each test-contexts/clean-contexts-fixture)

(use-fixtures :each
  (fn [f]
    (context-handler/reset-rejection-counters!)
    (input-handler/reset-rejection-counters!)
    (f)
    (context-handler/reset-rejection-counters!)
    (input-handler/reset-rejection-counters!)))

(defn- owned-server-context [player-uuid ctx-id]
  (assoc (ctx/new-server-context player-uuid :test-skill ctx-id)
         :input-state :active
         :last-keepalive-ms 1))

(deftest context-lifecycle-handlers-require-context-owner-test
  (with-redefs [uuid/player-uuid identity]
    (let [events (atom [])
          ctx-id "ctx-owned"]
      (ctx/register-context! (owned-server-context "p1" ctx-id))
      (ctx/ctx-on! ctx-id :test/channel #(swap! events conj %))

      (context-handler/handle-keepalive-context {:ctx-id ctx-id} "p2")
      (is (= 1 (:last-keepalive-ms (ctx/get-context ctx-id))))

      (context-handler/handle-channel-context {:ctx-id ctx-id
                                               :channel :test/channel
                                               :payload {:n 1}}
                                              "p2")
      (is (empty? @events))

      (context-handler/handle-terminate-context {:ctx-id ctx-id} "p2")
      (is (= ctx/STATUS-ALIVE (:status (ctx/get-context ctx-id))))

      (context-handler/handle-keepalive-context {:ctx-id ctx-id} "p1")
      (is (< 1 (:last-keepalive-ms (ctx/get-context ctx-id))))

      (context-handler/handle-channel-context {:ctx-id ctx-id
                                               :channel :test/channel
                                               :payload {:n 2}}
                                              "p1")
      (is (= [{:n 2}] @events))

      (context-handler/handle-terminate-context {:ctx-id ctx-id} "p1")
      (is (= ctx/STATUS-TERMINATED (:status (ctx/get-context ctx-id)))))))

(deftest input-handlers-require-context-owner-and-refresh-keepalive-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-input"]
      (ctx/register-context! (owned-server-context "p1" ctx-id))

      (input-handler/handle-key-tick-skill {:ctx-id ctx-id} "p2")
      (is (= 1 (:last-keepalive-ms (ctx/get-context ctx-id))))

      (input-handler/handle-key-tick-skill {:ctx-id ctx-id} "p1")
      (is (< 1 (:last-keepalive-ms (ctx/get-context ctx-id)))))))

(deftest keepalive-and-channel-ignore-non-alive-context-test
  (with-redefs [uuid/player-uuid identity]
    (let [events (atom [])
          ctx-id "ctx-not-alive"]
      (ctx/register-context! (assoc (owned-server-context "p1" ctx-id)
                                    :status ctx/STATUS-TERMINATED))
      (ctx/ctx-on! ctx-id :test/channel #(swap! events conj %))

      (context-handler/handle-keepalive-context {:ctx-id ctx-id} "p1")
      (context-handler/handle-channel-context {:ctx-id ctx-id
                                               :channel :test/channel
                                               :payload {:n 1}}
                                              "p1")
      (is (= 1 (:last-keepalive-ms (ctx/get-context ctx-id))))
      (is (empty? @events))
      (is (= 2 (get (context-handler/rejection-counters-snapshot) :ctx-not-alive 0))))))

(deftest key-down-does-not-let-other-player-reuse-existing-context-id-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-key-down"]
      (ctx/register-context! (assoc (ctx/new-server-context "p1" :test-skill ctx-id)
                                    :input-state :idle
                                    :last-keepalive-ms 1))

      (input-handler/handle-key-down-skill {:ctx-id ctx-id :skill-id :other-skill} "p2")
      (is (= "p1" (:player-uuid (ctx/get-context ctx-id))))
      (is (= :test-skill (:skill-id (ctx/get-context ctx-id))))
      (is (= :idle (:input-state (ctx/get-context ctx-id))))
      (is (= 1 (:last-keepalive-ms (ctx/get-context ctx-id)))))))

(deftest key-down-does-not-auto-establish-missing-context-test
  (with-redefs [uuid/player-uuid identity]
    (let [establish-calls (atom [])
          rt-calls (atom [])]
      (with-redefs [ctx-mgr/establish-context! (fn [player-uuid ctx-id skill-id]
                                                 (swap! establish-calls conj {:player-uuid player-uuid
                                                                              :ctx-id ctx-id
                                                                              :skill-id skill-id})
                                                 nil)
                    ctx-rt/handle-key-down! (fn [ctx-id _payload _terminate-fn]
                                              (swap! rt-calls conj ctx-id)
                                              true)]
        (is (nil? (input-handler/handle-key-down-skill {:ctx-id "ctx-missing"
                                                        :skill-id :arc-gen}
                                                       "p1")))
        (is (empty? @establish-calls))
        (is (empty? @rt-calls))
        (is (= 1 (get (input-handler/rejection-counters-snapshot) :ctx-not-found 0)))))))

(deftest key-input-ignores-owned-but-non-alive-context-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-constructed"
          down-calls (atom 0)]
      (ctx/register-context! (assoc (ctx/new-context "p1" :arc-gen)
                                    :id ctx-id
                                    :status ctx/STATUS-CONSTRUCTED))
      (with-redefs [ctx-rt/handle-key-down! (fn [_ctx-id _payload _terminate-fn]
                                              (swap! down-calls inc)
                                              true)]
        (is (nil? (input-handler/handle-key-down-skill {:ctx-id ctx-id
                                                        :skill-id :arc-gen}
                                                       "p1")))
        (is (= 0 @down-calls))))))

(deftest malformed-context-and-input-payloads-record-payload-invalid-test
  (with-redefs [uuid/player-uuid identity]
    (context-handler/handle-keepalive-context {:ctx-id nil} "p1")
    (context-handler/handle-channel-context {:ctx-id "ctx-x"
                                             :channel nil
                                             :payload {}}
                                            "p1")
    (input-handler/handle-key-down-skill {:ctx-id "" :skill-id :arc-gen} "p1")
    (is (= 2 (get (context-handler/rejection-counters-snapshot) :payload-invalid 0)))
    (is (= 1 (get (input-handler/rejection-counters-snapshot) :payload-invalid 0)))))

(deftest begin-link-rejects-malformed-or-foreign-existing-context-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-begin-owned"
          establish-calls (atom [])]
      (ctx/register-context! (owned-server-context "owner" ctx-id))
      (with-redefs [ctx-mgr/establish-context! (fn [& args]
                                                 (swap! establish-calls conj args))]
        (context-handler/handle-begin-link-context {:ctx-id nil :skill-id :arc-gen} "owner")
        (context-handler/handle-begin-link-context {:ctx-id ctx-id :skill-id :arc-gen} "attacker")
        (is (empty? @establish-calls))
        (is (= 1 (get (context-handler/rejection-counters-snapshot) :payload-invalid 0)))
        (is (= 1 (get (context-handler/rejection-counters-snapshot) :ctx-not-owner 0)))))))