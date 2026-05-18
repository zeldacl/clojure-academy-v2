(ns cn.li.mcmod.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.content :as content]))

(deftest register-content-contract-test
  (testing "content bootstrap call is best-effort and always returns nil"
    (is (nil? (content/register-content! "ac")))
    (is (nil? (content/register-content! "ac")))))

(deftest register-content-repeatability-test
  (testing "repeated calls remain stable and non-throwing"
    (dotimes [_ 5]
      (is (nil? (content/register-content! "ac"))))))

