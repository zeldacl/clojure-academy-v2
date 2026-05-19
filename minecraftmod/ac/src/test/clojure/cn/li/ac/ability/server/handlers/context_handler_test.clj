(ns cn.li.ac.ability.server.handlers.context-handler-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.handlers.context-handler :as context-handler]
            [cn.li.ac.ability.server.handlers.input-handler :as input-handler]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.test.support.contexts :as test-contexts]))

(use-fixtures :each test-contexts/clean-contexts-fixture)

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