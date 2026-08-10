(ns cn.li.fabric262.datagen.item-model-provider
  "Fabric 26.2 item model datagen provider.

  Emits item model JSON files from DSL item metadata.

  Fabric has no OBJ model loader, so a 3D item's `_3d` model is written as a
  plain vanilla model that only carries the display transforms and the atlas
  texture. The mesh is attached at bake time — see
  `cn.li.fabric262.client.obj-model-registration`."
  (:require [cn.li.platform.neutral.config :as modid]
            [cn.li.mcbase.datagen.gson-util :as gson-util]
            [cn.li.mcbase.datagen.item-model-provider-core :as item-model-core]
            [clojure.string :as str])
    (:import [com.google.gson Gson JsonElement]
           [java.util.concurrent CompletableFuture]
         [net.minecraft.data CachedOutput DataProvider PackOutput PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources Identifier]))

(defn- obj-3d-json
  "Vanilla-loadable stand-in for the `_3d` model: no parent and no elements, so
  it bakes to an empty mesh with our display transforms. Declaring `particle`
  gets the OBJ texture stitched onto the block atlas (that is the only slot
  `BlockModel#getMaterials` collects without elements); `default` is what the
  MTL's `map_Kd #default` resolves against."
  [mod-id {:keys [texture display]}]
  (let [texture-id (str mod-id ":" texture)]
    (cond-> {:textures {:particle texture-id
                        :default texture-id}}
      (seq display) (assoc :display display))))

(defn- model-json
  [mod-id {:keys [json obj-model] :as spec}]
  (if obj-model
    (obj-3d-json mod-id spec)
    json))

(defn- model-ref [model-name]
  {:type "minecraft:model" :model (str modid/mod-id ":item/" model-name)})

(defn- flat-item-model [{:keys [model-name json]}]
  (let [overrides (:overrides json)]
    (if (seq overrides)
      {:type "minecraft:range_dispatch"
       :property (str modid/mod-id ":energy")
       :fallback (model-ref model-name)
       :entries (->> overrides
                     (map (fn [{:keys [predicate model]}]
                            {:threshold (double (or (get predicate (str modid/mod-id ":energy")) 0.0))
                             :model (model-ref (last (str/split (str model) #"/")))}))
                     (sort-by :threshold) vec)}
      (model-ref model-name))))

(defn- item-definition [spec obj-bases]
  (let [name (str (:model-name spec))
        flat (flat-item-model spec)]
    {:model (if (contains? obj-bases name)
              {:type "minecraft:select"
               :property "minecraft:display_context"
               :cases [{:when "gui" :model flat}]
               :fallback (model-ref (str name "_3d"))}
              flat)}))

(defn- auxiliary-model-names [models]
  (into #{}
        (concat
          (mapcat (fn [{:keys [json]}]
                    (keep #(some-> (:model %) str (str/split #"/") last) (:overrides json))) models)
          (keep (fn [{:keys [model-name obj-model]}]
                  (when obj-model (str model-name))) models))))

(defn create-provider
    [^PackOutput output]
  (let [^String mod-id (str modid/mod-id)
      path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "models/item")
      item-path-provider (.createPathProvider output PackOutput$Target/RESOURCE_PACK "items")
      ^Gson gson (gson-util/create-pretty-gson)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [{:keys [all-item-count energy-tier-count simple-count models]} (item-model-core/gather-model-specs)
              auxiliary (auxiliary-model-names models)
              obj-bases (into #{} (keep (fn [{:keys [model-name obj-model]}]
                                          (when (and obj-model (str/ends-with? (str model-name) "_3d"))
                                            (subs (str model-name) 0 (- (count (str model-name)) 3))))) models)
              writes (atom [])]
          (doseq [{:keys [model-name] :as spec} models
                  :let [json (model-json mod-id spec)]]
            (let [target-path (.json ^PackOutput$PathProvider path-provider
                                     (Identifier/fromNamespaceAndPath mod-id model-name))
                  json-tree (.toJsonTree gson (gson-util/normalize-json json))]
              (swap! writes conj
                     (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))))
          (doseq [{:keys [model-name] :as spec} models
                  :when (not (contains? auxiliary (str model-name)))]
            (let [target-path (.json ^PackOutput$PathProvider item-path-provider
                                     (Identifier/fromNamespaceAndPath mod-id model-name))
                  json-tree (.toJsonTree gson (gson-util/normalize-json (item-definition spec obj-bases)))]
              (swap! writes conj
                     (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))))
          (println (str "[item-model-provider/fabric] summary: items=" all-item-count
                        ", energy-tier=" energy-tier-count
                        ", simple-model=" simple-count))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str mod-id " Item Model Provider")))))
