(ns cn.li.mcmod.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.content :as content]))

(deftest register-content-contract-test
  (testing "content bootstrap call is best-effort and always returns nil"
    (is (nil? (content/register-content! "example")))
    (is (nil? (content/register-content! "example")))))

(deftest register-content-repeatability-test
  (testing "repeated calls remain stable and non-throwing"
    (dotimes [_ 5]
      (is (nil? (content/register-content! "example"))))))

(deftest discover-and-register-all-content-contract-test
  (testing "content discovery is generic and best-effort"
    (is (sequential? (content/available-content-ids)))
    (is (nil? (content/register-all-content!)))))

