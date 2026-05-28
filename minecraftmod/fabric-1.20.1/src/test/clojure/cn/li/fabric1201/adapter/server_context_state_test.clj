(ns cn.li.fabric1201.adapter.server-context-state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.fabric1201.adapter.server-context :as server-context]))

(use-fixtures :each
  (fn [f]
    (server-context/call-with-server-context-runtime
      (server-context/create-server-context-runtime)
      (fn []
        (f)))))

(deftest get-server-defaults-to-nil-test
  (is (nil? (server-context/get-server))))

(deftest clear-current-server-resets-current-runtime-test
  (server-context/reset-current-server-for-test! :server-a)
  (is (= :server-a
         (server-context/get-server)))
  (server-context/clear-current-server!)
  (is (nil? (server-context/get-server))))

(deftest server-context-runtime-isolation-test
  (let [runtime-b (server-context/create-server-context-runtime)]
    (server-context/reset-current-server-for-test! :server-a)
    (server-context/call-with-server-context-runtime
      runtime-b
      (fn []
        (server-context/reset-current-server-for-test! :server-b)
        (is (= :server-b
               (server-context/get-server)))))
    (is (= :server-a
           (server-context/get-server)))))
