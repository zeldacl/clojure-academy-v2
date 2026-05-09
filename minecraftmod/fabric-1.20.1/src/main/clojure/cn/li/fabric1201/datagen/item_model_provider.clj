(ns cn.li.fabric1201.datagen.item-model-provider
  "Fabric 1.20.1 item model datagen provider.

  Emits item model JSON files from DSL item metadata."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.item-model-patterns :as item-model-patterns])
  (:import [com.google.gson JsonElement]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(defn- texture-path
  [texture-name]
  (str (str modid/*mod-id*) ":item/" texture-name))

(defn- normalize-parent
  [parent]
  (str (or parent "item/generated")))

(defn- simple-model-json
  [{:keys [model-texture model-parent]}]
  {:parent (normalize-parent model-parent)
   :textures {:layer0 (texture-path model-texture)}})

(defn- energy-tier-models-json
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

(defn- gather-model-specs
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
                         (energy-tier-models-json item-name
                                                  (get-in (item-dsl/get-item item-name)
                                                          [:properties :item-model-energy-levels])))
                       energy-tier-items)
               (for [simple-spec simple-items]
                 {:model-name (:item-name simple-spec)
                  :json (simple-model-json simple-spec)})))}))

(defn create-provider
  [output]
  (let [^String mod-id (str modid/*mod-id*)
  path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "models/item")
        gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [{:keys [all-item-count energy-tier-count simple-count models]} (gather-model-specs)
              writes (atom [])]
          (doseq [{:keys [model-name json]} models]
            (let [target-path (.json ^PackOutput$PathProvider path-provider (ResourceLocation. mod-id model-name))
                  json-tree (.toJsonTree gson (gson-util/normalize-json json))]
              (swap! writes conj
                     (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))))
          (println (str "[item-model-provider/fabric] summary: items=" all-item-count
                        ", energy-tier=" energy-tier-count
                        ", simple-model=" simple-count))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str mod-id " Item Model Provider")))))
