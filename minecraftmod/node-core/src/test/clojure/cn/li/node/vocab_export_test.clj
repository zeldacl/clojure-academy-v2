(ns cn.li.node.vocab-export-test
  "cn.li.node.schema-export's NEW surface-DSL vocabulary export path
   (export-vocab/export-ops/export-player-effects) -- distinct from the old
   export-descriptor/export-environment path, which schema_export_test.clj
   already covers and which this file must not touch (still the live
   production path via ac's final_catalog_service.clj)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.schema-export :as export]
            [cn.li.node.ops :as ops]
            [cn.li.node.test-fixtures :as fx]))

(deftest export-vocab-covers-every-node-in-stable-order-test
  (let [exported (export/export-vocab fx/vocab)]
    (is (= (sort (keys fx/vocab)) (mapv :id exported)))
    (is (every? #(contains? % :cost) exported))
    (is (every? #(contains? % :effects) exported))))

(deftest export-vocab-node-shape-test
  (let [[node] (filter #(= :combat/damage (:id %)) (export/export-vocab fx/vocab))]
    (is (= 3 (:cost node)))
    (is (= #{:world-write} (:effects node)))
    (is (false? (:pure? node)))
    (is (contains? (:params node) :target))
    (is (= :double (get-in node [:params :amount :type])))))

(deftest export-ops-covers-the-pure-op-table-test
  (let [exported (export/export-ops ops/table)]
    (is (= (sort (keys ops/table)) (mapv :id exported)))
    (is (every? :pure? exported))
    (is (every? #(= 0 (:cost %)) exported))
    (is (every? #(= #{} (:effects %)) exported))))

(deftest export-player-effects-matches-the-union-of-allowed-nodes-test
  (is (= #{:world-read :world-write}
         (export/export-player-effects fx/vocab #{:target/raycast :combat/damage})))
  (is (= #{} (export/export-player-effects fx/vocab #{}))))
