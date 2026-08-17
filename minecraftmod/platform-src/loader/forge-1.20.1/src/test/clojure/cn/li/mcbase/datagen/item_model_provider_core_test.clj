(ns cn.li.mcbase.datagen.item-model-provider-core-test
  "Regression tests for the shared item-model datagen helpers:
   item-model-tree builds the 1.21.4+ nested range_dispatch chain for the
   matter-unit damage + frame animation model (upstream ItemMatterUnit:
   per-damage models + `frame` flowing-liquid animation)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cn.li.mcbase.datagen.item-model-provider-core :as core]))

(def ^:private matter-specs
  {"matter_unit"
   {:model-name "matter_unit"
    :json {:parent "item/generated"
           :textures {:layer0 "academy:item/matter_unit"}
           :overrides [{:predicate {"academy:matter_kind" 1.0}
                        :model "academy:item/matter_unit_phase_liquid_0"}]}}
   "matter_unit_phase_liquid_0"
   {:model-name "matter_unit_phase_liquid_0"
    :json {:parent "item/generated"
           :textures {:layer0 "academy:item/matter_unit_phase_liquid_0"}
           :overrides [{:predicate {"academy:frame" 1.0}
                        :model "academy:item/matter_unit_phase_liquid_1"}
                       {:predicate {"academy:frame" 2.0}
                        :model "academy:item/matter_unit_phase_liquid_2"}
                       {:predicate {"academy:frame" 3.0}
                        :model "academy:item/matter_unit_phase_liquid_3"}]}}
   "matter_unit_phase_liquid_1"
   {:model-name "matter_unit_phase_liquid_1"
    :json {:parent "item/generated"
           :textures {:layer0 "academy:item/matter_unit_phase_liquid_1"}}}
   "matter_unit_phase_liquid_2"
   {:model-name "matter_unit_phase_liquid_2"
    :json {:parent "item/generated"
           :textures {:layer0 "academy:item/matter_unit_phase_liquid_2"}}}
   "matter_unit_phase_liquid_3"
   {:model-name "matter_unit_phase_liquid_3"
    :json {:parent "item/generated"
           :textures {:layer0 "academy:item/matter_unit_phase_liquid_3"}}}})

(deftest item-model-tree-nests-damage-then-frame-dispatch-test
  (let [tree (core/item-model-tree matter-specs "matter_unit")]
    (is (= "minecraft:range_dispatch" (:type tree)))
    (is (= "academy:matter_kind" (:property tree)))
    (is (= "minecraft:model" (:type (:fallback tree))))
    (let [entry (first (:entries tree))]
      (is (= 1.0 (:threshold entry)))
      ;; The damage entry resolves to the FILLED model's own dispatch: the
      ;; nested academy:frame range_dispatch with ascending thresholds.
      (is (= "academy:frame" (get-in entry [:model :property])))
      (is (= [1.0 2.0 3.0]
             (mapv :threshold (get-in entry [:model :entries]))))
      (is (= ["matter_unit_phase_liquid_1"
              "matter_unit_phase_liquid_2"
              "matter_unit_phase_liquid_3"]
             (mapv (comp #(last (str/split % #"/")) :model :model)
                   (get-in entry [:model :entries])))))))

(deftest item-model-tree-leaf-model-is-plain-reference-test
  (let [leaf (core/item-model-tree matter-specs "matter_unit_phase_liquid_1")]
    (is (= "minecraft:model" (:type leaf)))
    (is (str/ends-with? (:model leaf) ":item/matter_unit_phase_liquid_1"))))
