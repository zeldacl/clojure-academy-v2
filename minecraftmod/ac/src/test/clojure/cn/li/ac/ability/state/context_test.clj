(ns cn.li.ac.ability.state.context-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.ability.service.dispatcher :as ctx]))

(use-fixtures :each test-contexts/clean-contexts-fixture)

(deftest nested-update-context-test
  (let [c (ctx/new-server-context "p" :skill "ctx-nested")]
    (ctx/register-context! c)
    (ctx/update-context! "ctx-nested" assoc-in [:skill-state :a :b] 1)
    (is (= 1 (get-in (ctx/get-context "ctx-nested") [:skill-state :a :b])))
    (ctx/update-context! "ctx-nested" update-in [:skill-state :a] dissoc :b)
    (is (nil? (get-in (ctx/get-context "ctx-nested") [:skill-state :a :b])))))

(deftest buffer-message-while-constructed-test
  (let [c (ctx/new-context "p" :sk)]
    (ctx/register-context! c)
    (ctx/ctx-send-to-server! (:id c) :ch {:x 1})
    (is (= 1 (count (:message-buffer (ctx/get-context (:id c))))))))

(deftest get-all-contexts-for-player-test
  (let [a (ctx/new-server-context "p1" :s1 "id-a")
        b (ctx/new-server-context "p1" :s2 "id-b")
        c (ctx/new-server-context "p2" :s3 "id-c")]
    (ctx/register-context! a)
    (ctx/register-context! b)
    (ctx/register-context! c)
    (is (= 2 (count (ctx/get-all-contexts-for-player "p1"))))
    (is (= 1 (count (ctx/get-all-contexts-for-player "p2"))))))

(deftest transition-to-alive-flushes-buffer-test
  (let [c (ctx/new-context "p" :sk)
        id (:id c)
        flushed (atom [])]
    (ctx/register-context! c)
    (ctx/ctx-send-to-server! id :ch {:n 1})
    (ctx/transition-to-alive! id "srv-1" (fn [msg] (swap! flushed conj msg)))
    (is (= ctx/STATUS-ALIVE (:status (ctx/get-context id))))
    (is (= [{:channel :ch :payload {:n 1}}] @flushed))
    (is (empty? (:message-buffer (ctx/get-context id))))))
