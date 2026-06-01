(ns cn.li.ac.terminal.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.terminal.catalog :as catalog]))

(deftest catalog-contains-built-in-apps-test
  (is (= 6 (count catalog/apps)))
  (is (= #{:skill-tree :settings :tutorial :freq-transmitter :media-player :about}
         (set (catalog/app-ids))))
  (is (every? #(= #{:id :name :icon :description :category} (set (keys %)))
              catalog/apps)))

(deftest catalog-queries-test
  (is (= 6 (catalog/app-count)))
  (is (catalog/app-exists? :about))
  (is (false? (catalog/app-exists? :missing)))
  (is (= :about (:id (catalog/app-by-id :about))))
  (is (= 6 (count (catalog/ordered-apps)))))
