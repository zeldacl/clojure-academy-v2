(ns cn.li.mc1201.datagen.item-model-provider-core
  "Shared item model datagen helpers for loader-specific providers."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mc1201.datagen.item-model-patterns :as item-model-patterns]))

(defn- texture-path
  "Build a full texture ResourceLocation string from a texture name.
  If the name already contains a colon, return it as-is (full path e.g.
  'my_mod:block/phase_liquid'). Otherwise prepend modid:item/."
  [texture-name]
  (if (str/includes? (str texture-name) ":")
    (str texture-name)
    (str (str modid/mod-id) ":item/" texture-name)))

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
        mod-id (str modid/mod-id)]
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

(defn- fluid-bucket-model-entries
  "Generate item model entries for fluid bucket items from the fluid DSL.
  Bucket items are registered via Forge's fluid registration, not in item-dsl,
  so the standard gather-model-specs path misses them."
  []
  (keep (fn [fluid-id]
          (when-let [fluid-spec (registry-metadata/get-fluid-spec fluid-id)]
            (when (get-in fluid-spec [:block :has-bucket?])
              (let [still-texture (get-in fluid-spec [:rendering :still-texture])
                    bucket-registry-name (get-in fluid-spec [:block :bucket-registry-name])]
                (when (and bucket-registry-name still-texture)
                  {:model-name bucket-registry-name
                   :json {:parent "item/generated"
                          :textures {:layer0 (if (str/includes? still-texture ":")
                                               still-texture
                                               (str modid/mod-id ":item/" still-texture))}}})))))
        (registry-metadata/get-all-fluid-ids)))

(defn gather-model-specs
  []
  (let [all-item-names (item-dsl/list-items)
        energy-tier-items (filter #(item-model-patterns/energy-tier-item? (item-dsl/get-item %))
                                  all-item-names)
        obj-3d-items (filter #(item-model-patterns/obj-3d-item? (item-dsl/get-item %))
                              all-item-names)
        simple-items (keep (fn [item-name]
                             (let [item-spec (item-dsl/get-item item-name)]
                               (when-not (or (item-model-patterns/energy-tier-item? item-spec)
                                            (item-model-patterns/obj-3d-item? item-spec))
                                 (item-model-patterns/simple-model-spec item-name item-spec))))
                           all-item-names)
        bucket-entries (fluid-bucket-model-entries)]
    {:all-item-count (count all-item-names)
     :energy-tier-count (count energy-tier-items)
     :obj-3d-count (count obj-3d-items)
     :simple-count (count simple-items)
     :bucket-count (count bucket-entries)
     :models (vec
              (concat
               (mapcat (fn [item-name]
                         (energy-tier-model-entries item-name
                                                    (get-in (item-dsl/get-item item-name)
                                                            [:properties :item-model-energy-levels])))
                       energy-tier-items)
               (map (fn [item-name]
                      (let [item-spec (item-dsl/get-item item-name)]
                        (item-model-patterns/obj-3d-model-spec
                          item-name
                          (get-in item-spec [:properties :item-model-3d-obj]))))
                    obj-3d-items)
               (map simple-model-entry simple-items)
               bucket-entries))}))