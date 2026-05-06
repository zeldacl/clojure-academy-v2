(ns cn.li.ac.block.metal-former.recipes-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.block.metal-former.recipes :as recipes]))

(deftest mode-normalization-test
  (is (= :plate (recipes/normalize-mode nil)))
  (is (= :incise (recipes/normalize-mode "incise")))
  (is (= "academy:textures/guis/icons/icon_former_refine.png"
         (recipes/mode->icon-texture :refine))))

(deftest recipe-by-id-and-form-guard-test
  (is (some? (recipes/get-recipe-by-id "mf_plate_iron")))
  (is (nil? (recipes/can-form? nil nil nil :plate))))
