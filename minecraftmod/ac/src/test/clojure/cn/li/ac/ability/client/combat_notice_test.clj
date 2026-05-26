(ns cn.li.ac.ability.client.combat-notice-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.combat-notice :as notice]))

(defn- reset-notices-fixture [f]
  (notice/reset-notices-for-test!)
  (try
    (f)
    (finally
      (notice/reset-notices-for-test!))))

(use-fixtures :each reset-notices-fixture)

(deftest notices-are-transient-and-clearable-test
  (with-redefs [cn.li.ac.ability.client.combat-notice/now-ms (fn [] 1000)]
    (notice/show-notice! :hit {:text "Hit" :duration-ms 100 :color [1 2 3]}))
  (is (= #{:hit} (set (keys (notice/notices-snapshot)))))
  (is (= "Hit" (:text (notice/active-notice :hit 1050))))
  (is (nil? (notice/active-notice :hit 1200)))
  (is (empty? (notice/notices-snapshot))))

(deftest notice-reset-can-restore-snapshot-test
  (notice/show-notice! :a {:text "A"})
  (let [snapshot (notice/notices-snapshot)]
    (notice/reset-notices-for-test!)
    (is (empty? (notice/notices-snapshot)))
    (notice/reset-notices-for-test! snapshot)
    (is (contains? (notice/notices-snapshot) :a))
    (notice/clear-notice! :a)
    (is (empty? (notice/notices-snapshot)))))
