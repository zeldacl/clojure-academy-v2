(ns cn.li.ac.ability.registry.category-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.registry.category :as cat]))

(defn- reset-cats! [f]
  (reset! cat/category-registry {})
  (f)
  (reset! cat/category-registry {}))

(use-fixtures :each reset-cats!)

(deftest register-and-query-test
  (cat/register-category! {:id :esper
                          :name-key "cat.esper"
                          :icon "i"
                          :color [1.0 1.0 1.0 1.0]
                          :prog-incr-rate 1.25
                          :enabled true})
  (is (= 1.25 (cat/get-prog-incr-rate :esper)))
  (is (true? (cat/category-enabled? :esper)))
  (is (= "cat.esper" (:name-key (cat/get-category :esper))))
  (is (pos? (count (cat/get-all-categories)))))

(deftest prog-incr-rate-default-test
  (is (= 1.0 (cat/get-prog-incr-rate :unknown))))

(deftest disabled-category-test
  (cat/register-category! {:id :off
                          :name-key "cat.off"
                          :icon "i"
                          :color [0 0 0 1]
                          :prog-incr-rate 1.0
                          :enabled false})
  (is (false? (cat/category-enabled? :off))))
