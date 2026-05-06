(ns cn.li.mcmod.network.client-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.network.client :as client]
            [cn.li.mcmod.platform.dispatch :as dispatch]))

(defn- reset-client-state! [f]
  (reset! (var-get #'client/request-counter) 0)
  (reset! (var-get #'client/pending-requests) {})
  (reset! (var-get #'client/push-handlers) {})
  (f)
  (reset! (var-get #'client/request-counter) 0)
  (reset! (var-get #'client/pending-requests) {})
  (reset! (var-get #'client/push-handlers) {}))

(use-fixtures :each reset-client-state!)

(deftest send-to-server-request-id-contract-test
  (let [calls (atom [])]
    (with-redefs [client/send-request (fn [msg payload request-id]
                                        (swap! calls conj [msg payload request-id]))]
      (client/send-to-server "one-way" {:a 1})
      (client/send-to-server "req" {:b 2} identity)
      (is (= [["one-way" {:a 1} -1]
              ["req" {:b 2} 1]]
             @calls))
      (is (= #{1} (set (keys @(var-get #'client/pending-requests))))))))

(deftest handle-response-lifecycle-test
  (let [responses (atom [])]
    (swap! (var-get #'client/pending-requests) assoc 10 (fn [resp] (swap! responses conj resp)))
    (client/handle-response 10 {:ok true})
    (is (= [{:ok true}] @responses))
    (is (= nil (get @(var-get #'client/pending-requests) 10))))
  (testing "unknown request-id is ignored without throw"
    (is (= nil (client/handle-response 999 {:ok false}))))
  (testing "callback exception is swallowed"
    (swap! (var-get #'client/pending-requests) assoc 3 (fn [_] (throw (ex-info "cb-fail" {}))))
    (is (= nil (client/handle-response 3 {:x 1})))
    (is (= nil (get @(var-get #'client/pending-requests) 3)))))

(deftest push-handler-contract-test
  (let [calls (atom [])]
    (client/register-push-handler! "push/a" (fn [payload] (swap! calls conj payload)))
    (client/handle-push "push/a" {:x 1})
    (is (= [{:x 1}] @calls))
    (is (= nil (client/handle-push "missing" {}))))
  (testing "push handler exception is swallowed"
    (client/register-push-handler! "push/boom" (fn [_] (throw (ex-info "boom" {}))))
    (is (= nil (client/handle-push "push/boom" {:a 1})))))

(deftest default-send-request-contract-test
  (binding [dispatch/*platform-version* :unknown]
    (try
      (client/send-request "m" {} 0)
      (is false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :unknown (:version (ex-data e))))))))
