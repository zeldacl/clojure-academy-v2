(ns cn.li.combat.dsl-vocabulary-test
  "Editor-palette completeness: every real node in cn.li.combat.dsl-
   vocabulary/nodes must resolve to a real :category (never
   :uncategorized) and carry a non-nil :i18n key -- see that namespace's
   own category-by-namespace/i18n-for docstrings for why this is
   structurally guaranteed rather than hand-checked per node, and why a
   gap here would mean category-by-namespace is missing an entry for a
   real node namespace, not that any individual node was skipped."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.dsl-vocabulary :as vocab]))

(deftest every-node-has-a-real-category-test
  (let [uncategorized (into {} (filter (fn [[_ spec]] (= :uncategorized (:category spec)))) vocab/nodes)]
    (is (= {} uncategorized)
        (str "nodes with no category-by-namespace entry for their namespace: " (keys uncategorized)))))

(deftest every-node-has-an-i18n-key-test
  (let [missing (into {} (filter (fn [[_ spec]] (nil? (:i18n spec)))) vocab/nodes)]
    (is (= {} missing))))

(deftest i18n-keys-are-unique-test
  (let [keys (map :i18n (vals vocab/nodes))]
    (is (= (count keys) (count (set keys))))))

(deftest category-for-is-public-and-matches-node-category-test
  (doseq [[id spec] vocab/nodes]
    (is (= (:category spec) (vocab/category-for id)) id)))
