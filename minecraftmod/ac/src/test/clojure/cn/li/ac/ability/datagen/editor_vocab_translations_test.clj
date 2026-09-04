(ns cn.li.ac.ability.datagen.editor-vocab-translations-test
  "Coverage check for the node editor's palette i18n keys: this is the
   REAL corpus (combat-core's + vfx-core's real dsl-vocabulary, node-
   core's real ops table, combat-core's real lib fns), not a synthetic
   fixture -- combat-core/vfx-core/node-core's own dsl-vocabulary-test/
   vocab-export-test files can only assert internal consistency (every id
   has SOME :i18n key); only ac can assert that key actually resolves to
   a label, since only ac owns the datagen translation assembly."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.datagen.editor-vocab-translations :as editor-vocab]
            [cn.li.node.schema-export :as schema-export]
            [cn.li.node.ops :as ops]
            [cn.li.combat.api :as combat-api]
            [cn.li.vfx.api :as vfx-api]))

(defn- all-i18n-keys []
  (into #{}
        (map :i18n)
        (concat
         (schema-export/export-vocab combat-api/skill-vocab)
         (schema-export/export-vocab vfx-api/scene-vocab)
         (schema-export/export-ops ops/table)
         (schema-export/export-fns combat-api/skill-lib-fns combat-api/skill-vocab-category-for))))

(deftest every-palette-entry-i18n-key-resolves-in-en-us-test
  (let [en (:en_us (editor-vocab/translation-map))
        keys (all-i18n-keys)
        missing (remove #(contains? en %) keys)]
    (is (seq keys) "sanity: the corpus itself must be non-empty")
    (is (= [] missing) (str "i18n keys with no resolvable en_us label: " missing))))

(deftest labels-are-non-blank-test
  (let [en (:en_us (editor-vocab/translation-map))]
    (is (every? #(and (string? %) (seq %)) (vals en)))))
