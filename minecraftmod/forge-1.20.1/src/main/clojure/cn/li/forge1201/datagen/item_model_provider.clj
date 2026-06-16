(ns cn.li.forge1201.datagen.item-model-provider
  "Forge 1.20.1 Item Model datagen — generates model JSON from item DSL.

  Supported model types:
  - :item/generated (simple items with a single layer texture)
  - Energy-tier items (generated base + half/full tier models with :energy predicate)
  - forge:obj 3D items (OBJ model with display transforms per perspective)

  Optional :item-model-energy-levels in :properties generates base + tiered
  sibling models and overrides on predicate <modid>:energy (see client
  `energy-item-model-properties`).

  Optional :item-model-3d-obj in :properties generates a forge:obj loader model
  with perspective-specific display transforms."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.resource-location :as rl]
            [cn.li.mc1201.datagen.item-model-provider-core :as item-model-core])
  (:import [net.minecraft.data PackOutput]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.common.data ExistingFileHelper]
           [net.minecraftforge.client.model.generators ItemModelProvider ItemModelBuilder ModelFile$ExistingModelFile]))

(defn- apply-model-spec!
  [^ItemModelProvider provider ^ExistingFileHelper exfile-helper {:keys [model-name json]}]
  (let [^ResourceLocation parent-rl (or (rl/parse-resource-location (:parent json))
                                        (.mcLoc provider "item/generated"))
        ^ItemModelBuilder builder (.withExistingParent provider (str model-name) parent-rl)]
    (doseq [[layer texture-id] (:textures json)]
      (.texture builder (name layer) ^ResourceLocation (rl/parse-resource-location texture-id modid/*mod-id*)))
    ;; Forge model pipeline reverses the override array at load time,
    ;; so write overrides in reverse order (half first, then full) so that
    ;; after reversal the highest threshold (1.0) is checked first.
    (doseq [{:keys [predicate model]} (reverse (:overrides json))]
      (let [override-builder (.override builder)
            model-file (ModelFile$ExistingModelFile. (rl/parse-resource-location model modid/*mod-id*) exfile-helper)]
        (doseq [[predicate-id value] predicate]
          (.predicate override-builder (rl/parse-resource-location predicate-id modid/*mod-id*) (float value)))
        (.model override-builder model-file)
        (.end override-builder)))
    builder))

;; ============================================================================
;; OBJ 3D model generation
;; ============================================================================

(defn- json-float-array
  "Build a Gson JsonArray from numeric values."
  ^com.google.gson.JsonArray [xs]
  (let [arr (com.google.gson.JsonArray.)]
    (doseq [x xs] (.add arr (Float/valueOf (float x))))
    arr))

(defn- perspective-json
  ^com.google.gson.JsonObject [{:keys [rotation scale translation]}]
  (let [obj (com.google.gson.JsonObject.)]
    (when rotation (.add obj "rotation" (json-float-array rotation)))
    (when scale (.add obj "scale" (json-float-array scale)))
    (when translation (.add obj "translation" (json-float-array translation)))
    obj))

(defn- obj-model-json
  ^com.google.gson.JsonObject [mod-id {:keys [obj-model texture display]}]
  (let [json (com.google.gson.JsonObject.)
        _ (.addProperty json "loader" "forge:obj")
        _ (.addProperty json "model" (str mod-id ":" obj-model))
        _ (.addProperty json "flip-v" true)
        _ (.addProperty json "detectCullableFaces" false)
        ;; Material: default → texture
        mats (doto (com.google.gson.JsonObject.)
               (.add "default" (doto (com.google.gson.JsonObject.)
                                 (.addProperty "texture" (str mod-id ":textures/" texture)))))
        _ (.add json "custom" mats)
        ;; Display transforms per perspective
        display-json (reduce-kv (fn [^com.google.gson.JsonObject obj k v]
                                  (.add obj (name k) (perspective-json v))
                                  obj)
                                (com.google.gson.JsonObject.)
                                display)]
    (.add json "display" display-json)
    json))

(defn- apply-obj-model-spec!
  "Generate a forge:obj model JSON for a 3D item via datagen.

  Uses ItemModelBuilder.customLoader to emit a forge:obj loader model with
  perspective display transforms matching the original AcademyCraft
  BakedModelForTEISR transform matrices.

  In Forge 1.20.1 (47.1.0), customLoader takes a single BiFunction argument,
  not a BiFunction + ExistingFileHelper pair. The BiFunction receives the
  parent builder and existing-file helper, and must return a
  CustomLoaderBuilder whose toJson() produces the final model JSON."
  [^ItemModelProvider provider ^ExistingFileHelper _exfile-helper {:keys [model-name] :as spec}]
  (let [^ItemModelBuilder builder (.withExistingParent provider (str model-name) "item/generated")
        mod-id (str modid/*mod-id*)
        loader-rl (rl/parse-resource-location "forge:obj")]
    (.customLoader builder
                   (reify java.util.function.BiFunction
                     (apply [_ parent-builder helper]
                       (proxy [net.minecraftforge.client.model.generators.CustomLoaderBuilder] [loader-rl parent-builder helper]
                         (toJson [_]
                           (obj-model-json mod-id spec))))))
    builder))

(defn create
  "Create Item Model DataProvider instance (factory signature: PackOutput -> DataProvider)"
  [^PackOutput pack-output ^ExistingFileHelper exfile-helper]
  (proxy [ItemModelProvider] [pack-output modid/*mod-id* exfile-helper]
    (registerModels []
      (let [this-provider ^ItemModelProvider this
            {:keys [all-item-count energy-tier-count obj-3d-count simple-count bucket-count models]}
            (item-model-core/gather-model-specs)]
        ;; Standard models: item/generated, energy-tier, fluid buckets
        ;; OBJ 3D models: forge:obj loader
        (doseq [model-spec models]
          (if (:obj-model model-spec)
            (apply-obj-model-spec! this-provider exfile-helper model-spec)
            (apply-model-spec! this-provider exfile-helper model-spec)))
        (println (str "[item-model-provider] summary: items=" all-item-count
                      ", energy-tier=" energy-tier-count
                      ", obj-3d=" obj-3d-count
                      ", simple-model=" simple-count
                      ", buckets=" bucket-count))))))
