(ns cn.li.ac.ability.context-protocol-v2-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]))

(use-fixtures :each test-contexts/clean-contexts-fixture)

(def ^:private test-client-context-owner {:logical-side :client :session-id :test-session})
(def ^:private test-server-context-owner {:logical-side :server :session-id :test-session})

(deftest constructed-context-buffers-and-flushes-in-order-on-establish-test
  (let [ctx-id "ctx-protocol-buffer"
        route-sends (atom [])
        flushed (atom [])]
    (ctx/register-route-fns! {:to-server (fn [ctx-id* channel payload _ctx-map]
                                           (swap! route-sends conj [ctx-id* channel payload]))
                              :to-client nil
                              :to-except-local nil})
        (ctx/register-context! (assoc (ctx/new-context "p1" :arc-gen test-client-context-owner) :id ctx-id))
        (binding [ctx/*context-owner* test-client-context-owner]
          (ctx/ctx-send-to-server! ctx-id :first {:n 1})
          (ctx/ctx-send-to-server! ctx-id :second {:n 2})

          (is (empty? @route-sends))
          (is (= [{:channel :first :payload {:n 1}}
            {:channel :second :payload {:n 2}}]
           (:message-buffer (ctx/get-context ctx-id))))

          (ctx/transition-to-alive! ctx-id "sid-protocol" #(swap! flushed conj %))

          (is (= ctx/STATUS-ALIVE (:status (ctx/get-context ctx-id))))
          (is (= "sid-protocol" (:server-id (ctx/get-context ctx-id))))
          (is (empty? (:message-buffer (ctx/get-context ctx-id))))
          (is (= [{:channel :first :payload {:n 1}}
            {:channel :second :payload {:n 2}}]
           @flushed))

          (ctx/ctx-send-to-server! ctx-id :third {:n 3})
          (is (= [[ctx-id :third {:n 3}]] @route-sends)))))

(deftest terminated-context-drops-route-messages-test
  (let [ctx-id "ctx-protocol-terminated"
        route-sends (atom [])]
    (ctx/register-route-fns! {:to-server (fn [ctx-id* channel payload _ctx-map]
                                           (swap! route-sends conj [ctx-id* channel payload]))
                              :to-client (fn [ctx-id* channel payload _ctx-map]
                                           (swap! route-sends conj [ctx-id* channel payload]))
                              :to-except-local (fn [ctx-id* channel payload _ctx-map]
                                                 (swap! route-sends conj [ctx-id* channel payload]))})
    (ctx/register-context! (ctx/new-server-context "p1" :arc-gen ctx-id test-server-context-owner))
    (binding [ctx/*context-owner* test-server-context-owner]
      (ctx/terminate-context! ctx-id nil)

      (ctx/ctx-send-to-server! ctx-id :late {:n 1})
      (ctx/ctx-send-to-client! ctx-id :late {:n 2})
      (ctx/ctx-send-to-except-local! ctx-id :late {:n 3})

      (is (= ctx/STATUS-TERMINATED (:status (ctx/get-context ctx-id))))
      (is (empty? @route-sends)))))

(deftest same-id-client-and-server-contexts-are-side-isolated-test
  (let [ctx-id "ctx-side-shared"
        client-sends (atom [])
        server-sends (atom [])]
    (ctx/register-route-fns! {:logical-side :client
                              :session-id :session-a
                              :to-server (fn [ctx-id* channel payload _ctx-map]
                                           (swap! client-sends conj [ctx-id* channel payload]))})
    (ctx/register-route-fns! {:logical-side :server
                              :session-id :session-a
                              :to-client (fn [ctx-id* channel payload _ctx-map]
                                           (swap! server-sends conj [ctx-id* channel payload]))})
    (ctx/register-context! (assoc (ctx/new-context "p1" :arc-gen {:logical-side :client :session-id :session-a})
                                  :id ctx-id
                                  :status ctx/STATUS-ALIVE))
    (ctx/register-context! (ctx/new-server-context "p1" :arc-gen ctx-id {:logical-side :server :session-id :session-a}))

    (is (= 2 (count (ctx/get-all-contexts))))
    (is (= :client (:logical-side (ctx/get-context {:logical-side :client :session-id :session-a} ctx-id))))
    (is (= :server (:logical-side (ctx/get-context {:logical-side :server :session-id :session-a} ctx-id))))

    (binding [ctx/*context-owner* {:logical-side :client :session-id :session-a}]
      (ctx/ctx-send-to-server! ctx-id :up {:n 1}))
    (binding [ctx/*context-owner* {:logical-side :server :session-id :session-a}]
      (ctx/ctx-send-to-client! ctx-id :down {:n 2}))

    (is (= [[ctx-id :up {:n 1}]] @client-sends))
    (is (= [[ctx-id :down {:n 2}]] @server-sends))))

(deftest same-id-server-contexts-pass-explicit-context-to-route-test
  (let [ctx-id "ctx-owned-route"
        route-calls (atom [])
        owner-a {:logical-side :server :session-id :session-a}
        owner-b {:logical-side :server :session-id :session-b}]
    (ctx/register-route-fns! {:logical-side :server
                              :session-id :session-a
                              :to-client (fn [ctx-id* channel payload ctx-map]
                                           (swap! route-calls conj [:to-client (:player-uuid ctx-map) ctx-id* channel payload]))
                              :to-except-local (fn [ctx-id* channel payload ctx-map]
                                                 (swap! route-calls conj [:to-except-local (:player-uuid ctx-map) ctx-id* channel payload]))})
    (ctx/register-route-fns! {:logical-side :server
                              :session-id :session-b
                              :to-client (fn [ctx-id* channel payload ctx-map]
                                           (swap! route-calls conj [:to-client (:player-uuid ctx-map) ctx-id* channel payload]))
                              :to-except-local (fn [ctx-id* channel payload ctx-map]
                                                 (swap! route-calls conj [:to-except-local (:player-uuid ctx-map) ctx-id* channel payload]))})
    (ctx/register-context! (ctx/new-server-context "player-a" :arc-gen ctx-id owner-a))
    (ctx/register-context! (ctx/new-server-context "player-b" :arc-gen ctx-id owner-b))

    (binding [ctx/*context-owner* owner-a]
      (ctx/ctx-send-to-client! ctx-id :fx {:n 1})
      (ctx/ctx-send-to-except-local! ctx-id :fx {:n 2}))
    (binding [ctx/*context-owner* owner-b]
      (ctx/ctx-send-to-client! ctx-id :fx {:n 3})
      (ctx/ctx-send-to-except-local! ctx-id :fx {:n 4}))

    (is (= [[:to-client "player-a" ctx-id :fx {:n 1}]
            [:to-except-local "player-a" ctx-id :fx {:n 2}]
            [:to-client "player-b" ctx-id :fx {:n 3}]
            [:to-except-local "player-b" ctx-id :fx {:n 4}]]
           @route-calls))))