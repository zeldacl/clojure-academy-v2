(ns cn.li.ac.ability.state.context-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.ac.ability.effects.state :as state]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(use-fixtures :each
  (fn [f]
    (test-contexts/clean-contexts-fixture
     #(test-player/clean-player-states-fixture f))))

(def ^:private test-server-context-owner {:logical-side :server :session-id :test-session})
(def ^:private test-client-context-owner {:logical-side :client :session-id :test-session})

(deftest context-creation-requires-explicit-owner-test
  (testing "ownerless client contexts fail fast"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Context owner requires :session-id"
                          (ctx/new-context "p" :skill))))
  (testing "ownerless server contexts fail fast"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Context owner requires :session-id"
                          (ctx/new-server-context "p" :skill "ctx-ownerless")))))

(deftest nested-update-context-test
  (let [c (ctx/new-server-context "p" :skill "ctx-nested" test-server-context-owner)]
    (test-player/seed-player-state!
     "p"
     {:context-registry {"ctx-nested" {:id "ctx-nested" :skill-id :skill :status :constructed}}})
    (ctx/register-context! c)
    (binding [runtime-hooks/*player-state-owner* test-player/test-player-state-owner
              ctx/*context-owner* test-server-context-owner]
      (state/execute-assoc-state! {:ctx-id "ctx-nested" :player-id "p"} {:k [:a :b] :v 1})
      (is (= 1 (get-in (ctx/get-context "ctx-nested") [:skill-state :a :b])))
      (state/execute-assoc-state! {:ctx-id "ctx-nested" :player-id "p"} {:k [] :v {:a {}}})
      (is (nil? (get-in (ctx/get-context "ctx-nested") [:skill-state :a :b]))))))

(deftest buffer-message-while-constructed-test
  (let [c (ctx/new-context "p" :sk test-client-context-owner)]
    (ctx/register-context! c)
    (binding [ctx/*context-owner* test-client-context-owner]
      (ctx/ctx-send-to-server! (:id c) :ch {:x 1})
      (is (= 1 (count (:message-buffer (ctx/get-context (:id c)))))))))

(deftest get-all-contexts-for-player-test
  (let [a (ctx/new-server-context "p1" :s1 "id-a" test-server-context-owner)
        b (ctx/new-server-context "p1" :s2 "id-b" test-server-context-owner)
        c (ctx/new-server-context "p2" :s3 "id-c" test-server-context-owner)]
    (ctx/register-context! a)
    (ctx/register-context! b)
    (ctx/register-context! c)
    (is (= 2 (count (ctx/get-all-contexts-for-player "p1"))))
    (is (= 1 (count (ctx/get-all-contexts-for-player "p2"))))))

(deftest get-all-contexts-for-player-can-filter-by-owner-test
  (let [client-a {:logical-side :client :session-id [:session-a "p1"]}
        client-b {:logical-side :client :session-id [:session-b "p1"]}
        server-a {:logical-side :server :session-id "p1"}
        ctx-a (ctx/new-context "p1" :s1 client-a)
        ctx-b (ctx/new-context "p1" :s2 client-b)
        ctx-c (ctx/new-server-context "p1" :s3 "id-server" server-a)]
    (ctx/register-context! ctx-a)
    (ctx/register-context! ctx-b)
    (ctx/register-context! ctx-c)
    (is (= 3 (count (ctx/get-all-contexts-for-player "p1"))))
    (is (= [:s1]
           (mapv :skill-id (ctx/get-all-contexts-for-player client-a "p1"))))
    (is (= [:s2]
           (mapv :skill-id (ctx/get-all-contexts-for-player client-b "p1"))))
    (is (= [:s3]
           (mapv :skill-id (ctx/get-all-contexts-for-player server-a "p1"))))))

(deftest transition-to-alive-flushes-buffer-test
  (let [c (ctx/new-context "p" :sk test-client-context-owner)
        id (:id c)
        flushed (atom [])]
    (ctx/register-context! c)
    (binding [ctx/*context-owner* test-client-context-owner]
      (ctx/ctx-send-to-server! id :ch {:n 1})
      (ctx/transition-to-alive! id "srv-1" (fn [msg] (swap! flushed conj msg)))
      (is (= ctx/STATUS-ALIVE (:status (ctx/get-context id))))
      (is (= [{:channel :ch :payload {:n 1}}] @flushed))
      (is (empty? (:message-buffer (ctx/get-context id)))))))

(deftest update-missing-context-does-not-create-phantom-test
  (binding [ctx/*context-owner* test-server-context-owner]
    (state/execute-assoc-state! {:ctx-id "missing-context" :player-id "p"}
                                {:k :last-keepalive-ms :v 1})
    (is (nil? (ctx/get-context "missing-context"))))
  (is (empty? (ctx/get-all-contexts))))

(deftest string-context-lookup-requires-owner-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Opaque ctx-id resolution requires \*context-owner\* or an explicit owner"
                        (ctx/get-context "ctx-ownerless"))))

(deftest active-contexts-filters-terminated-and-supports-player-query-test
  (let [alive (ctx/new-server-context "p1" :s1 "ctx-alive" test-server-context-owner)
        terminated (assoc (ctx/new-server-context "p1" :s2 "ctx-dead" test-server-context-owner)
                          :status ctx/STATUS-TERMINATED)
        other (ctx/new-server-context "p2" :s3 "ctx-other" test-server-context-owner)]
    (ctx/register-context! alive)
    (ctx/register-context! terminated)
    (ctx/register-context! other)
    (is (= #{"ctx-alive" "ctx-other"}
           (set (keys (ctx/active-contexts)))))
    (is (= [:s1]
           (mapv :skill-id (ctx/active-contexts "p1"))))))
