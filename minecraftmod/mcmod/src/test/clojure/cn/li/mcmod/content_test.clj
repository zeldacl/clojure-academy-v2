(ns cn.li.mcmod.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.content :as content]))

(defn noop [])

(deftest register-content-contract-test
  (testing "content metadata is explicit"
    (is (thrown? clojure.lang.ExceptionInfo (content/register-content! "example")))
    (is (nil? (content/register-content! {:namespace "cn.li.mcmod.content-test"
                                          :function "noop"})))))

(deftest register-content-repeatability-test
  (testing "repeated explicit calls remain stable"
    (dotimes [_ 5]
      (is (nil? (content/register-content! {:namespace "cn.li.mcmod.content-test"
                                            :function "noop"}))))))

(deftest discover-and-register-all-content-contract-test
  (testing "content discovery is generic and best-effort"
    (is (sequential? (content/available-content-ids)))
    (is (nil? (content/register-all-content!)))))
