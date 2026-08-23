(ns cn.li.combat.source-nodes-test
  "Registration-shape coverage for the seven source nodes (NODE_LANGUAGE.md
   section 5, plus :ability/context added later for v2's {:ref [:context
   ...]} value form). Not execution coverage (see source_runtime_test.clj
   for that) -- this only proves the descriptors themselves are well-formed
   against node-core's v3 registry and show up in the schema export a
   future editor would consume."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.set :as set]
            [cn.li.node.descriptor :as node]
            [cn.li.node.schema-export :as export]
            [cn.li.combat.source-nodes :as source-nodes]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (source-nodes/install!)
    (f)
    (node/reset-for-test!)))

(def ^:private expected-ids
  #{:ability/caster :ability/tunable :ability/budget
    :ability/progression :ability/cooldown :ability/invariant :ability/context})

(deftest all-six-registered-as-source-layer-test
  (doseq [id expected-ids]
    (let [d (node/descriptor id)]
      (is (some? d) (str id " must be registered"))
      (is (= :source (:layer d)))
      (is (nil? (:impl d)) "source nodes must never carry :impl"))))

(deftest caster-has-no-inputs-and-many-outputs-test
  (let [d (node/descriptor :ability/caster)]
    (is (empty? (:inputs d)))
    (is (contains? (:outputs d) :eye))
    (is (contains? (:outputs d) :aim))
    (is (contains? (:outputs d) :charge-ticks))))

(deftest name-scoped-sources-declare-reads-environment-test
  (doseq [id #{:ability/tunable :ability/budget :ability/progression :ability/cooldown :ability/invariant :ability/context}]
    (let [d (node/descriptor id)]
      (is (contains? (:inputs d) :name))
      (is (seq (:reads-environment d)) (str id " must declare which document-level table it reads")))))

(deftest schema-export-includes-every-source-node-test
  (let [catalog (export/export-catalog)
        exported-ids (set (map :id catalog))]
    (is (set/subset? expected-ids exported-ids))))

(deftest source-nodes-are-not-composites-test
  ;; composite.clj's expand only treats :layer :mid as a macro-substitution
  ;; target; a :source node reached structurally (no :children/:body) must
  ;; pass through unchanged instead of being mistaken for a composite call.
  (doseq [id expected-ids]
    (is (not= :mid (:layer (node/descriptor id))))))
