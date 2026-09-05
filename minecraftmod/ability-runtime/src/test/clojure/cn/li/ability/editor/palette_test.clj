(ns cn.li.ability.editor.palette-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.editor.palette :as palette]
            [cn.li.node.ops :as ops]
            [cn.li.node.test-fixtures :as fx]))

(def ^:private category-for (constantly :targeting))

(deftest build-covers-vocab-and-ops-and-fns-test
  (let [entries (palette/build {:vocab fx/vocab :ops ops/table :fns fx/fns :category-for category-for})]
    (is (= (+ (count fx/vocab) (count ops/table) (count fx/fns)) (count entries)))
    (is (some #(= :node (:source %)) entries))
    (is (some #(= :op (:source %)) entries))
    (is (some #(= :fn (:source %)) entries))))

(deftest build-defaults-fns-and-category-for-to-empty-test
  (let [entries (palette/build {:vocab fx/vocab :ops ops/table})]
    (is (= (+ (count fx/vocab) (count ops/table)) (count entries)))))

(deftest entries-are-sorted-by-category-then-id-test
  (let [entries (palette/build {:vocab fx/vocab :ops ops/table :fns fx/fns :category-for category-for})]
    (is (= entries (sort-by (juxt :category :id) entries)))))

(deftest group-by-category-groups-every-entry-test
  (let [entries (palette/build {:vocab fx/vocab :ops ops/table})
        grouped (palette/group-by-category entries)]
    (is (= (count entries) (reduce + (map count (vals grouped)))))))

(deftest filter-by-effects-excludes-entries-outside-the-allowlist-test
  (let [entries (palette/build {:vocab fx/vocab :ops ops/table})
        filtered (palette/filter-by-effects entries #{:world-read})]
    (is (every? #(clojure.set/subset? (:effects %) #{:world-read}) filtered))
    (is (some #(= :target/raycast (:id %)) filtered))
    (is (not-any? #(= :combat/damage (:id %)) filtered))))

(deftest find-by-id-returns-the-matching-entry-test
  (let [entries (palette/build {:vocab fx/vocab :ops ops/table})]
    (is (= :target/raycast (:id (palette/find-by-id entries :target/raycast))))
    (is (nil? (palette/find-by-id entries :no/such-id)))))
