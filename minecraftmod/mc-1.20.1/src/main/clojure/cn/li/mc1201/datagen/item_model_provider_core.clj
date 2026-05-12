(ns cn.li.mc1201.datagen.item-model-provider-core
  "Shared item model datagen helpers for loader-specific providers."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.mc1201.datagen.item-model-patterns :as item-model-patterns]))

(defn- texture-path
  [texture-name]
  (str (str modid/*mod-id*) ":item/" texture-name))

(defn- normalize-parent
  [parent]
  (str (or parent "item/generated")))

(defn- simple-model-entry
  [{:keys [item-name model-texture model-parent]}]
  {:model-name item-name
   :json {:parent (normalize-parent model-parent)
          :textures {:layer0 (texture-path model-texture)}}})

(defn- energy-tier-model-entries
  [item-id {:keys [texture-empty texture-half texture-full]}]
  (let [{:keys [base empty-texture half-texture full-texture half-model full-model]}
        (item-model-patterns/energy-tier-model-spec item-id {:texture-empty texture-empty
                                                             :texture-half texture-half
                                                             :texture-full texture-full})
        mod-id (str modid/*mod-id*)]
    [{:model-name half-model
      :json {:parent "item/generated"
             :textures {:layer0 (texture-path half-texture)}}}
     {:model-name full-model
      :json {:parent "item/generated"
             :textures {:layer0 (texture-path full-texture)}}}
     {:model-name base
      :json {:parent "item/generated"
             :textures {:layer0 (texture-path empty-texture)}
             :overrides [{:predicate {(str mod-id ":energy") 1.0}
                          :model (str mod-id ":item/" full-model)}
                         {:predicate {(str mod-id ":energy") 0.5}
                          :model (str mod-id ":item/" half-model)}]}}]))

(defn gather-model-specs
  []
  (let [all-item-names (item-dsl/list-items)
        energy-tier-items (filter #(item-model-patterns/energy-tier-item? (item-dsl/get-item %))
                                  all-item-names)
        simple-items (keep (fn [item-name]
                             (let [item-spec (item-dsl/get-item item-name)]
                               (when-not (item-model-patterns/energy-tier-item? item-spec)
                                 (item-model-patterns/simple-model-spec item-name item-spec))))
                           all-item-names)]
    {:all-item-count (count all-item-names)
     :energy-tier-count (count energy-tier-items)
     :simple-count (count simple-items)
     :models (vec
              (concat
               (mapcat (fn [item-name]
                         (energy-tier-model-entries item-name
                                                    (get-in (item-dsl/get-item item-name)
                                                            [:properties :item-model-energy-levels])))
                       energy-tier-items)
               (map simple-model-entry simple-items)))}))