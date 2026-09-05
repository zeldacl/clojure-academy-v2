(ns cn.li.vfx.dsl-vocabulary-test
  "Editor-palette completeness: every real node in cn.li.vfx.dsl-
   vocabulary/nodes must resolve to a real :category (never
   :uncategorized) and carry a non-nil :i18n key -- see combat-core's
   identical test for why this shape check matters (it is what a node
   editor's palette actually consumes)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.vfx.dsl-vocabulary :as vocab]))

(deftest every-node-has-a-real-category-test
  (let [uncategorized (into {} (filter (fn [[_ spec]] (= :uncategorized (:category spec)))) vocab/nodes)]
    (is (= {} uncategorized)
        (str "nodes with no category-by-id entry: " (keys uncategorized)))))

(deftest every-node-has-an-i18n-key-test
  (let [missing (into {} (filter (fn [[_ spec]] (nil? (:i18n spec)))) vocab/nodes)]
    (is (= {} missing))))

(deftest i18n-keys-are-unique-test
  (let [keys (map :i18n (vals vocab/nodes))]
    (is (= (count keys) (count (set keys))))))
