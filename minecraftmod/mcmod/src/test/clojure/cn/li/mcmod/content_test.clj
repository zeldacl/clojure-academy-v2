(ns cn.li.mcmod.content-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.content :as content]))

(deftest ensure-content-init-registered-contract-test
  (testing "content bootstrap call is best-effort and always returns nil"
    (is (nil? (content/ensure-content-init-registered!)))
    (is (nil? (content/ensure-content-init-registered!)))))

(deftest ensure-content-init-registered-repeatability-test
  (testing "repeated calls remain stable and non-throwing"
    (dotimes [_ 5]
      (is (nil? (content/ensure-content-init-registered!))))))

