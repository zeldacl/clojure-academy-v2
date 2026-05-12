(ns cn.li.forge1201.setup.lifecycle-listeners-smoke-test
  "Smoke tests for lifecycle listener setup.
  These verify that listener registration functions exist and are callable."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.forge1201.setup.lifecycle-listeners :as listeners]))

(deftest lifecycle-listener-functions-exist
  "Verify that all lifecycle listener registration functions are defined"
  
  (testing "common lifecycle listener registration exists"
    (is (fn? listeners/register-common-lifecycle-listeners!)))
  
  (testing "client hooks registration exists"
    (is (fn? listeners/register-client-hooks!)))
  
  (testing "key mappings registration exists"
    (is (fn? listeners/register-client-key-mappings!))))

(deftest listener-registration-callability
  "Verify that listener registration functions can be invoked"
  
  (testing "client hooks can be registered without errors"
    ;; This is a basic smoke test
    (is (nil? (listeners/register-client-hooks!))
        "register-client-hooks! should complete without error")))
