(ns cn.li.neoforge262.setup.mod-bus-smoke-test
  "Smoke tests for mod-bus setup functions."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.neoforge262.setup.mod-bus :as mod-bus]))

(deftest mod-bus-functions-exist
  (testing "config phase function is available"
    (is (fn? mod-bus/register-config-phase!))
    (is (fn? mod-bus/register-registry-phase!))))

(deftest config-registration-callable
  (testing "register-all! is callable from bridge module"
    (let [bridge-ns (symbol "cn.li.neoforge262.config.bridge")]
      (is (some? (resolve (symbol (str bridge-ns) "register-all!")))))))
