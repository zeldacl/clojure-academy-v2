(ns cn.li.mc1201.block.container-default-behavior-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mc1201.block.logic-compile :as lc])
  (:import [net.minecraft.world.item ItemStack]
           [net.minecraft.core Direction]))

(deftest worldly-container-fallback-contract-test
  (let [c (.-container (lc/compile-tile-logic {:container {}}))]
    (is (= 0 (.getSize c nil)))
    (is (identical? ItemStack/EMPTY (.getItem c nil 0)))
    (is (identical? ItemStack/EMPTY (.removeItem c nil 0 1)))
    (is (identical? ItemStack/EMPTY (.removeItemNoUpdate c nil 0)))
    (is (true? (.stillValid c nil nil)))
    (is (= 0 (alength (.getSlotsForFace c nil Direction/UP))))
    (is (false? (.canPlaceItemThroughFace c nil 0 ItemStack/EMPTY Direction/DOWN)))
    (is (true? (.canTakeItemThroughFace c nil 0 ItemStack/EMPTY Direction/DOWN)))))
