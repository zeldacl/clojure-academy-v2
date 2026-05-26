(ns cn.li.ac.ability.context-message-order-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.handlers.context-handler :as context-handler]
            [cn.li.ac.ability.server.handlers.input-handler :as input-handler]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.server.service.context-runtime :as ctx-rt]
            [cn.li.ac.ability.util.uuid :as uuid]))

(use-fixtures :each test-contexts/clean-contexts-fixture)

(use-fixtures :each
  (fn [f]
    (context-handler/reset-rejection-counters!)
    (input-handler/reset-rejection-counters!)
    (f)
    (context-handler/reset-rejection-counters!)
    (input-handler/reset-rejection-counters!)))

(defn- server-owner [player-uuid]
  {:logical-side :server
   :session-id player-uuid})

(defn- get-owned-context [player-uuid ctx-id]
  (ctx/get-context (server-owner player-uuid) ctx-id))

(defn- seed-owned-alive-context!
  [player-uuid ctx-id]
  (ctx/register-context!
   (assoc (ctx/new-server-context player-uuid :arc-gen ctx-id (server-owner player-uuid))
          :input-state :idle
          :last-keepalive-ms 1)))

(deftest out-of-order-key-tick-and-key-up-before-key-down-are-ignored-test
  (let [ctx-id "ctx-order-1"]
    (seed-owned-alive-context! "p1" ctx-id)
    (is (nil? (ctx-rt/handle-key-tick! ctx-id {:ctx-id ctx-id :skill-id :arc-gen})))
    (is (nil? (ctx-rt/handle-key-up! ctx-id {:ctx-id ctx-id :skill-id :arc-gen})))
    (let [ctx-map (ctx/get-context ctx-id)]
      (is (= ctx/STATUS-ALIVE (:status ctx-map)))
      (is (= :idle (:input-state ctx-map))))))

(deftest out-of-order-key-messages-are-counted-at-handler-boundary-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-order-handler"]
      (seed-owned-alive-context! "p1" ctx-id)
      (is (nil? (input-handler/handle-key-tick-skill {:ctx-id ctx-id :skill-id :arc-gen} "p1")))
      (is (nil? (input-handler/handle-key-up-skill {:ctx-id ctx-id :skill-id :arc-gen} "p1")))
      (is (= ctx/STATUS-ALIVE (:status (get-owned-context "p1" ctx-id))))
      (is (= :idle (:input-state (get-owned-context "p1" ctx-id))))
      (is (= 2 (get (input-handler/rejection-counters-snapshot) :message-out-of-order 0))))))

(deftest duplicate-terminate-messages-are-idempotent-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-order-2"
          sends (atom 0)]
      (seed-owned-alive-context! "p1" ctx-id)
      (with-redefs [ctx-mgr/send-terminated-context! (fn [_] (swap! sends inc))]
        (context-handler/handle-terminate-context {:ctx-id ctx-id} "p1")
        (context-handler/handle-terminate-context {:ctx-id ctx-id} "p1"))
      (is (= 1 @sends))
      (is (= ctx/STATUS-TERMINATED (:status (get-owned-context "p1" ctx-id)))))))

(deftest keepalive-arriving-after-terminate-is-rejected-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-order-3"]
      (seed-owned-alive-context! "p1" ctx-id)
      (context-handler/handle-terminate-context {:ctx-id ctx-id} "p1")
      (context-handler/handle-keepalive-context {:ctx-id ctx-id} "p1")
      (let [ctx-map (get-owned-context "p1" ctx-id)]
        (is (= ctx/STATUS-TERMINATED (:status ctx-map)))
        (is (= 1 (:last-keepalive-ms ctx-map))))
      (is (= 1 (get (context-handler/rejection-counters-snapshot) :ctx-not-alive 0))))))
