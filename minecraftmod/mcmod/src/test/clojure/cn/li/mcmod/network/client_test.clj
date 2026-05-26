(ns cn.li.mcmod.network.client-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as client]
            [cn.li.mcmod.platform.dispatch :as dispatch]))

(defn- reset-client-state! [f]
  (client/reset-client-state-for-test!)
  (try
    (f)
    (finally
      (client/reset-client-state-for-test!))))

(use-fixtures :each reset-client-state!)

(deftest send-to-server-request-id-contract-test
  (let [calls (atom [])]
    (with-redefs [client/send-request (fn [msg payload request-id]
                                        (swap! calls conj [msg payload request-id]))]
      (binding [runtime-hooks/*client-session-id* :session-a]
        (client/send-to-server "one-way" {:a 1})
        (client/send-to-server "req" {:b 2} identity))
      (is (= [["one-way" {:a 1} -1]
              ["req" {:b 2} 1]]
             @calls))
      (is (= #{[(binding [runtime-hooks/*client-session-id* :session-a]
                  (client/client-owner-key nil)) 1]}
             (set (keys (:pending-requests (client/client-state-snapshot)))))))))

(deftest ownerless-send-requires-client-session-test
  (with-redefs [client/send-request (fn [& _] nil)]
    (binding [runtime-hooks/*client-session-id* nil]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Client network owner requires :client-session-id"
                            (client/send-to-server "req" {} identity))))))

(deftest handle-response-lifecycle-test
  (let [responses (atom [])]
    (with-redefs [client/send-request (fn [& _] nil)]
      (binding [runtime-hooks/*client-session-id* :session-a]
        (client/send-to-server "req" {} (fn [resp] (swap! responses conj resp)))))
    (binding [runtime-hooks/*client-session-id* :session-a]
      (client/handle-response 1 {:ok true}))
    (is (= [{:ok true}] @responses))
    (is (empty? (:pending-requests (client/client-state-snapshot)))))
  (testing "unknown request-id is ignored without throw"
    (binding [runtime-hooks/*client-session-id* :session-a]
      (is (= nil (client/handle-response 999 {:ok false})))))
  (testing "callback exception is swallowed"
    (with-redefs [client/send-request (fn [& _] nil)]
      (binding [runtime-hooks/*client-session-id* :session-a]
        (client/send-to-server "req" {} (fn [_] (throw (ex-info "cb-fail" {}))))))
    (binding [runtime-hooks/*client-session-id* :session-a]
      (is (= nil (client/handle-response 2 {:x 1}))))
    (is (empty? (:pending-requests (client/client-state-snapshot))))))

(deftest owner-scoped-pending-requests-test
  (let [owner-a {:client-session-id :session-a :screen-id :screen}
        owner-b {:client-session-id :session-b :screen-id :screen}
        responses (atom [])]
    (with-redefs [client/send-request (fn [& _] nil)]
      (client/send-to-server owner-a "req" {} (fn [resp] (swap! responses conj [:a resp])))
      (client/send-to-server owner-b "req" {} (fn [resp] (swap! responses conj [:b resp]))))
    (is (= #{[(client/client-owner-key owner-a) 1]
             [(client/client-owner-key owner-b) 1]}
           (set (keys (:pending-requests (client/client-state-snapshot))))))
    (client/handle-response owner-b 1 {:ok :b})
    (is (= [[:b {:ok :b}]] @responses))
    (is (= #{[(client/client-owner-key owner-a) 1]}
           (set (keys (:pending-requests (client/client-state-snapshot))))))
    (client/clear-owner-state! owner-a)
    (is (empty? (:pending-requests (client/client-state-snapshot))))))

(deftest pending-requests-expire-by-timeout-test
  (let [owner {:client-session-id :session-a :screen-id :screen :timeout-ms 1}]
    (with-redefs [client/send-request (fn [& _] nil)]
      (client/send-to-server owner "req" {} identity))
    (is (= [[(client/client-owner-key owner) 1]]
           (client/expire-pending-requests! (+ (System/currentTimeMillis) 10000))))
    (is (empty? (:pending-requests (client/client-state-snapshot))))))

(deftest push-handler-contract-test
  (let [calls (atom [])]
    (client/register-push-handler! "push/a" (fn [payload] (swap! calls conj payload)))
    (client/handle-push "push/a" {:x 1})
    (is (= [{:x 1}] @calls))
    (is (= nil (client/handle-push "missing" {}))))
  (testing "push handler exception is swallowed"
    (client/register-push-handler! "push/boom" (fn [_] (throw (ex-info "boom" {}))))
    (is (= nil (client/handle-push "push/boom" {:a 1})))))

(deftest owner-scoped-push-handler-test
  (let [owner-a {:client-session-id :session-a :screen-id :screen-a}
        owner-b {:client-session-id :session-a :screen-id :screen-b}
        calls (atom [])]
    (client/register-push-handler! "push/state" (fn [payload] (swap! calls conj [:static payload])))
    (client/register-push-handler! owner-a "push/state" (fn [payload] (swap! calls conj [:a payload])))
    (client/handle-push owner-a "push/state" {:x 1})
    (client/handle-push owner-b "push/state" {:x 2})
  (is (= [[:a {:x 1}]] @calls))
    (client/clear-owner-state! owner-a)
    (client/handle-push owner-a "push/state" {:x 3})
  (is (= [[:a {:x 1}]] @calls))))

(deftest default-send-request-contract-test
  (binding [dispatch/*platform-version* :unknown]
    (try
      (client/send-request "m" {} 0)
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :unknown (:version (ex-data e))))))))
