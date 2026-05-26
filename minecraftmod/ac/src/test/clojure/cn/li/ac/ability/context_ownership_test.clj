(ns cn.li.ac.ability.context-ownership-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.handlers.context-handler :as context-handler]
            [cn.li.ac.ability.server.handlers.input-handler :as input-handler]
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
          :input-state :active
          :last-keepalive-ms 1)))

(deftest forged-player-cannot-terminate-or-send-channel-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-own-1"
          events (atom [])]
      (seed-owned-alive-context! "owner" ctx-id)
      (binding [ctx/*context-owner* (server-owner "owner")]
        (ctx/ctx-on! ctx-id :fx #(swap! events conj %)))

      (context-handler/handle-channel-context {:ctx-id ctx-id
                                               :channel :fx
                                               :payload {:n 1}}
                                              "attacker")
      (context-handler/handle-terminate-context {:ctx-id ctx-id} "attacker")

      (is (empty? @events))
      (is (= ctx/STATUS-ALIVE (:status (get-owned-context "owner" ctx-id))))
      (is (= 1 (:last-keepalive-ms (get-owned-context "owner" ctx-id))))
      (is (= 2 (get (context-handler/rejection-counters-snapshot) :ctx-not-found 0))))))

(deftest forged-player-cannot-drive-key-input-test
  (with-redefs [uuid/player-uuid identity]
    (let [ctx-id "ctx-own-2"
          down-calls (atom 0)]
      (seed-owned-alive-context! "owner" ctx-id)
      (with-redefs [ctx-rt/handle-key-down! (fn [_ctx-id _payload _terminate-fn]
                                              (swap! down-calls inc)
                                              true)]
        (is (nil? (input-handler/handle-key-down-skill {:ctx-id ctx-id :skill-id :arc-gen}
                                                       "attacker"))))
      (is (= 0 @down-calls))
      (is (= 1 (:last-keepalive-ms (get-owned-context "owner" ctx-id))))
      (is (= 1 (get (input-handler/rejection-counters-snapshot) :ctx-not-found 0))))))
