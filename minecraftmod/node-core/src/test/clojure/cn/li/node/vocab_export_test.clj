(ns cn.li.node.vocab-export-test
  "cn.li.node.schema-export's surface-DSL vocabulary export path
   (export-vocab/export-ops/export-fns/export-player-effects) -- distinct
   from the old export-descriptor/export-environment path,
   schema_export_test.clj's own concern."
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

(deftest export-ops-every-entry-has-a-real-category-and-i18n-test
  (let [exported (export/export-ops ops/table)
        uncategorized (filter #(= :uncategorized (:category %)) exported)]
    (is (= [] uncategorized)
        (str "ops with no category-by-namespace entry: " (mapv :id uncategorized)))
    (is (every? #(some? (:i18n %)) exported))
    (is (= (count exported) (count (set (map :i18n exported)))) "i18n keys must be unique")))

(def ^:private synthetic-fns
  "A minimal cn.li.node.surface/normalize-shaped :defn doc map, standing
   in for cn.li.combat.lib/fns without node-core depending on combat-core
   (wrong dependency direction -- node-core is the zero-project-deps
   module)."
  {:target/hold-destination
   {:kind :defn :id :target/hold-destination
    :params [{:name 'origin :type :vec3} {:name 'distance :type :double}]
    :body [] :returns nil}})

(deftest export-fns-shape-test
  (let [exported (export/export-fns synthetic-fns (fn [id] (if (= "target" (namespace id)) :targeting :uncategorized)))
        [entry] exported]
    (is (= [:target/hold-destination] (mapv :id exported)))
    (is (= :targeting (:category entry)))
    (is (= "editor.fn.target.hold_destination" (:i18n entry)))
    (is (contains? (:params entry) :origin))
    (is (= :vec3 (get-in entry [:params :origin :type])))
    (is (false? (:pure? entry)))
    (is (= #{} (:effects entry)))))

(deftest export-player-effects-matches-the-union-of-allowed-nodes-test
  (is (= #{:world-read :world-write}
         (export/export-player-effects fx/vocab #{:target/raycast :combat/damage})))
  (is (= #{} (export/export-player-effects fx/vocab #{}))))
