(ns cn.li.ac.terminal.catalog-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [cn.li.ac.terminal.catalog :as catalog]))

(deftest catalog-contains-built-in-apps-test
  (is (= 6 (count catalog/apps)))
  (is (= #{:skill-tree :settings :tutorial :freq-transmitter :media-player :about}
         (set (catalog/app-ids))))
  ;; Core keys are mandatory; :pre-installed? and :icons are legitimate optionals
  ;; (settings/tutorial/about ship pre-installed, tutorial animates its icon).
  (let [required #{:id :name :icon :description :category}
        allowed (into required #{:pre-installed? :icons})]
    (is (every? #(set/subset? required (set (keys %))) catalog/apps))
    (is (every? #(set/subset? (set (keys %)) allowed) catalog/apps))))

(deftest catalog-queries-test
  (is (= 6 (catalog/app-count)))
  (is (catalog/app-exists? :about))
  (is (false? (catalog/app-exists? :missing)))
  (is (= :about (:id (catalog/app-by-id :about))))
  (is (= 6 (count (catalog/ordered-apps)))))
