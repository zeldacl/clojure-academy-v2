(ns cn.li.ac.block.imag-fusor.recipes-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]))

(deftest recipe-lookup-and-can-craft-guards-test
  (let [r (recipes/get-recipe-by-id "if_crystal_low_to_normal")]
    (is (= "if_crystal_low_to_normal" (:id r)))
    (is (nil? (recipes/can-craft? nil nil nil 0 0)))))
