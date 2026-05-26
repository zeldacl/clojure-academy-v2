(ns cn.li.mcmod.network.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.network.server :as server]))

(defn- reset-handlers! [f]
  (server/reset-handlers-for-test!)
  (try
    (f)
    (finally
      (server/reset-handlers-for-test!))))

(use-fixtures :each reset-handlers!)

(deftest handle-request-core-test
  (let [responses (atom [])]
    (server/register-handler "msg/a" (fn [payload _] {:ok (:x payload)}))
    (server/handle-request "msg/a" 10 {:x 7} :player
                           (fn [req-id resp] (swap! responses conj [req-id resp])))
    (is (= [[10 {:ok 7}]] @responses)))
  (testing "nil handler response is normalized to empty map"
    (let [responses (atom [])]
      (server/register-handler "msg/b" (fn [_ _] nil))
      (server/handle-request "msg/b" 1 {} :player
                             (fn [req-id resp] (swap! responses conj [req-id resp])))
      (is (= [[1 {}]] @responses)))))

(deftest handle-request-edge-cases-test
  (testing "one-way request-id does not trigger respond-fn"
    (let [called (atom false)]
      (server/register-handler "one-way" (fn [_ _] {:ok true}))
      (server/handle-request "one-way" -1 {} :player (fn [_ _] (reset! called true)))
      (is (= false @called))))
  (testing "missing handler returns no-handler response"
    (let [responses (atom [])]
      (server/handle-request "missing" 2 {} :player
                             (fn [req-id resp] (swap! responses conj [req-id resp])))
      (is (= [[2 {:success false :error "no-handler"}]] @responses)))))

(deftest handle-request-exception-contract-test
  (testing "handler exception returns structured error response"
    (let [responses (atom [])]
      (server/register-handler "boom" (fn [_ _] (throw (ex-info "broken" {}))))
      (server/handle-request "boom" 6 {} :player
                             (fn [req-id resp] (swap! responses conj [req-id resp])))
      (is (= 1 (count @responses)))
      (is (= 6 (ffirst @responses)))
      (is (= false (get-in @responses [0 1 :success])))
      (is (= "broken" (get-in @responses [0 1 :error]))))))

(deftest handler-registration-policy-test
  (let [handler (fn [payload _player] payload)]
    (server/register-handler "same" handler)
    (server/register-handler "same" handler)
    (is (= #{"same"} (set (keys (:handlers (server/handlers-snapshot))))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Conflicting network handler id"
         (server/register-handler "same" (fn [_ _] {:other true}))))))

(deftest frozen-handlers-reject-registration-test
  (server/freeze-handlers!)
  (is (true? (:frozen? (server/handlers-snapshot))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Network server handlers are frozen"
       (server/register-handler "late" (fn [_ _] nil)))))
