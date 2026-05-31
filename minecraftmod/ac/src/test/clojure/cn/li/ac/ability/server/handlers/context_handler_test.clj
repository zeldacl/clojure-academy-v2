(ns cn.li.ac.ability.server.handlers.context-handler-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.handlers.context-handler :as context-handler]
            [cn.li.ac.ability.server.handlers.input-handler :as input-handler]
            [cn.li.ac.ability.service.context-manager :as ctx-mgr]
            [cn.li.ac.ability.service.context-state :as ctx-rt]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.test.support.contexts :as test-contexts]))

(use-fixtures :each test-contexts/clean-contexts-fixture)

(def ^:private test-server-session-id [:server :handler-test])

(defn- server-owner [player-uuid]
  {:logical-side :server
   :server-session-id test-server-session-id
   :session-id [test-server-session-id player-uuid]
   :player-uuid player-uuid})

(defn- with-server-player-owner
  [player-uuid f]
  (binding [runtime-hooks/*player-state-owner* {:server-session-id test-server-session-id
                                                :player-uuid player-uuid}]
    (f)))

(defn- owned-server-context [player-uuid ctx-id]
  (assoc (ctx/new-server-context player-uuid :test-skill ctx-id (server-owner player-uuid))
         :input-state :active
         :last-keepalive-ms 1))

(defn- get-owned-context [player-uuid ctx-id]
  (ctx/get-context (server-owner player-uuid) ctx-id))

(deftest context-lifecycle-handlers-require-context-owner-test
  (with-redefs [uuid/player-uuid identity]
    (let [events (atom [])
          ctx-id "ctx-owned"]
      (ctx/register-context! (owned-server-context "p1" ctx-id))
      (binding [ctx/*context-owner* (server-owner "p1")]
        (ctx/ctx-on! ctx-id :test/channel #(swap! events conj %)))

      (with-server-player-owner "p2"
        #(context-handler/handle-keepalive-context {:ctx-id ctx-id} "p2"))
      (is (= 1 (:last-keepalive-ms (get-owned-context "p1" ctx-id))))

      (with-server-player-owner "p2"
        #(context-handler/handle-channel-context {:ctx-id ctx-id
                                                  :channel :test/channel
                                                  :payload {:n 1}}
                                                 "p2"))
      (is (empty? @events))

      (with-server-player-owner "p2"
        #(context-handler/handle-terminate-context {:ctx-id ctx-id} "p2"))
      (is (= ctx/STATUS-ALIVE (:status (get-owned-context "p1" ctx-id))))

      (with-server-player-owner "p1"
        #(context-handler/handle-keepalive-context {:ctx-id ctx-id} "p1"))
      (is (< 1 (:last-keepalive-ms (get-owned-context "p1" ctx-id))))

      (with-server-player-owner "p1"
        #(context-handler/handle-channel-context {:ctx-id ctx-id
                                                  :channel :test/channel
                                                  :payload {:n 2}}
                                                 "p1"))
      (is (= [{:n 2}] @events))

      (with-server-player-owner "p1"
        #(context-handler/handle-terminate-context {:ctx-id ctx-id} "p1"))
      (is (= ctx/STATUS-TERMINATED (:status (get-owned-context "p1" ctx-id)))))))

(deftest input-handlers-require-context-owner-and-refresh-keepalive-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-input"]
      (ctx/register-context! (owned-server-context "p1" ctx-id))

      (with-server-player-owner "p2"
        #(input-handler/handle-key-tick-skill {:ctx-id ctx-id} "p2"))
      (is (= 1 (:last-keepalive-ms (get-owned-context "p1" ctx-id))))

      (with-server-player-owner "p1"
        #(input-handler/handle-key-tick-skill {:ctx-id ctx-id} "p1"))
      (is (< 1 (:last-keepalive-ms (get-owned-context "p1" ctx-id)))))))

(deftest slot-key-tick-handler-refreshes-keepalive-without-dispatching-runtime-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-keepalive-only"
          dispatch-calls (atom [])]
      (ctx/register-context! (owned-server-context "p1" ctx-id))
      (with-redefs [ctx-rt/handle-key-tick! (fn [& args]
                                              (swap! dispatch-calls conj args)
                                              true)]
        (with-server-player-owner "p1"
          #(input-handler/handle-key-tick-skill {:ctx-id ctx-id} "p1"))
        (is (empty? @dispatch-calls))
        (is (< 1 (:last-keepalive-ms (get-owned-context "p1" ctx-id))))))))

(deftest keepalive-and-channel-ignore-non-alive-context-test
  (with-redefs [uuid/player-uuid identity]
    (let [events (atom [])
          ctx-id "ctx-not-alive"]
      (ctx/register-context! (assoc (owned-server-context "p1" ctx-id)
                                    :status ctx/STATUS-TERMINATED))
      (binding [ctx/*context-owner* (server-owner "p1")]
        (ctx/ctx-on! ctx-id :test/channel #(swap! events conj %)))

      (with-server-player-owner "p1"
        #(context-handler/handle-keepalive-context {:ctx-id ctx-id} "p1"))
      (with-server-player-owner "p1"
        #(context-handler/handle-channel-context {:ctx-id ctx-id
                                                  :channel :test/channel
                                                  :payload {:n 1}}
                                                 "p1"))
      (is (= 1 (:last-keepalive-ms (get-owned-context "p1" ctx-id))))
      (is (empty? @events)))))

(deftest key-down-does-not-let-other-player-reuse-existing-context-id-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-key-down"]
      (ctx/register-context! (assoc (ctx/new-server-context "p1" :test-skill ctx-id (server-owner "p1"))
                                    :input-state :idle
                                    :last-keepalive-ms 1))

      (with-server-player-owner "p2"
        #(input-handler/handle-key-down-skill {:ctx-id ctx-id :skill-id :other-skill} "p2"))
      (is (= "p1" (:player-uuid (get-owned-context "p1" ctx-id))))
      (is (= :test-skill (:skill-id (get-owned-context "p1" ctx-id))))
      (is (= :idle (:input-state (get-owned-context "p1" ctx-id))))
      (is (= 1 (:last-keepalive-ms (get-owned-context "p1" ctx-id)))))))

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
        (is (nil? (with-server-player-owner "p1"
               #(input-handler/handle-key-down-skill {:ctx-id "ctx-missing"
                        :skill-id :arc-gen}
                       "p1"))))
        (is (empty? @establish-calls))
        (is (empty? @rt-calls))))))

(deftest key-input-ignores-owned-but-non-alive-context-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-constructed"
          down-calls (atom 0)]
      (ctx/register-context! (assoc (ctx/new-server-context "p1" :arc-gen ctx-id (server-owner "p1"))
                                    :id ctx-id
                                    :status ctx/STATUS-CONSTRUCTED))
      (with-redefs [ctx-rt/handle-key-down! (fn [_ctx-id _payload _terminate-fn]
                                              (swap! down-calls inc)
                                              true)]
        (is (nil? (with-server-player-owner "p1"
               #(input-handler/handle-key-down-skill {:ctx-id ctx-id
                        :skill-id :arc-gen}
                       "p1"))))
        (is (= 0 @down-calls))))))

(deftest malformed-context-and-input-payloads-record-payload-invalid-test
  (with-redefs [uuid/player-uuid identity]
    (is (nil? (with-server-player-owner "p1"
               #(context-handler/handle-keepalive-context {:ctx-id nil} "p1"))))
    (is (nil? (with-server-player-owner "p1"
               #(context-handler/handle-channel-context {:ctx-id "ctx-x"
                                                         :channel nil
                                                         :payload {}}
                                                        "p1"))))
    (is (nil? (with-server-player-owner "p1"
               #(input-handler/handle-key-down-skill {:ctx-id "" :skill-id :arc-gen} "p1"))))))

(deftest begin-link-rejects-malformed-or-owned-mismatch-context-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-begin-owned"
          establish-calls (atom [])]
      (ctx/register-context! (assoc (owned-server-context "owner" ctx-id)
                                    :session-id [test-server-session-id "attacker"]))
      (with-redefs [ctx-mgr/establish-context! (fn [& args]
                                                 (swap! establish-calls conj args))]
        (is (nil? (with-server-player-owner "owner"
                   #(context-handler/handle-begin-link-context {:ctx-id nil :skill-id :arc-gen} "owner"))))
        (is (nil? (with-server-player-owner "attacker"
                   #(context-handler/handle-begin-link-context {:ctx-id ctx-id :skill-id :arc-gen} "attacker"))))
        (is (empty? @establish-calls))))))

(deftest same-client-context-id-is-isolated-by-server-player-owner-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "cid-1"
          establish-calls (atom [])]
      (with-redefs [ctx-mgr/establish-context! (fn [player-uuid ctx-id skill-id]
                                                 (swap! establish-calls conj [player-uuid ctx-id skill-id])
                                                 (ctx/register-context!
                                                  (assoc (ctx/new-server-context player-uuid skill-id ctx-id)
                                                         :input-state :idle)))]
        (with-server-player-owner "p1"
          #(context-handler/handle-begin-link-context {:ctx-id ctx-id :skill-id :railgun} "p1"))
        (with-server-player-owner "p2"
          #(context-handler/handle-begin-link-context {:ctx-id ctx-id :skill-id :meltdowner} "p2")))
      (is (= [["p1" ctx-id :railgun]
              ["p2" ctx-id :meltdowner]]
             @establish-calls))
      (is (= :railgun (:skill-id (get-owned-context "p1" ctx-id))))
      (is (= :meltdowner (:skill-id (get-owned-context "p2" ctx-id)))))))