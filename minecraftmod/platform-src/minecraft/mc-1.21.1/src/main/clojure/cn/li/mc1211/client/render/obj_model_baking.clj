(ns cn.li.mc1211.client.render.obj-model-baking
  "Loader-neutral OBJ composite item baking.

  Discovers DSL items with `:item-model-3d-obj` and installs
  {@link cn.li.mc1211.client.render.item.ObjCompositeBakedModel}. Loaders only
  register model-bus events and supply the baking registry map plus an
  ItemOverrides factory.

  The factory is a loader concern: an energy-tier item's predicate overrides
  capture their target BakedModels while baking, so a charged item resolves to
  the raw flat tier model and would drop back to a 2D icon in hand. Only a
  custom ItemOverrides can re-wrap that result, and its no-arg super ctor is
  reachable on Forge/NeoForge but private on Fabric."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.mcbase.datagen.item-model-patterns :as model-patterns]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.resources.model BakedModel ModelResourceLocation]
           [net.minecraft.resources ResourceLocation]
           [cn.li.mcver ResourceLocations]
           [java.util Map]
           [cn.li.mc1211.client.render.item ObjCompositeBakedModel]))

(defn- obj-3d-item-ids
  "Item DSL ids that request a 3D world model beside the flat GUI icon."
  []
  (set (filter #(model-patterns/obj-3d-item? (item-dsl/get-item %))
               (item-dsl/list-items))))

(defn- item-id->basename
  [item-id]
  (model-patterns/registry-model-basename item-id))

(defn obj-3d-item-specs
  "Per-item OBJ metadata for loader-side mesh registration."
  []
  (mapv (fn [item-id]
          (let [basename (item-id->basename item-id)
                {:keys [obj-model texture]} (get-in (item-dsl/get-item item-id)
                                                    [:properties :item-model-3d-obj])]
            {:item-id item-id
             :basename basename
             :model-path (str "item/" basename "_3d")
             :obj-path (or obj-model (str "models/" basename ".obj"))
             :texture (or texture (str "models/" basename))}))
        (obj-3d-item-ids)))

(defn- inventory-mrl
  [mod-id model-path]
  (ModelResourceLocation. (ResourceLocations/of (str mod-id) (str model-path)) "inventory"))

(defn additional-obj-inventory-model-locations
  "ModelResourceLocations for each item's `_3d` inventory variant.
  Loaders register these on their model-bus RegisterAdditional event."
  []
  (let [mod-id (str modid/mod-id)]
    (mapv (fn [item-id]
            (inventory-mrl mod-id (str (item-id->basename item-id) "_3d")))
          (obj-3d-item-ids))))

(defn install-obj-composite-models!
  "Replace each 3D item's inventory baked model with ObjCompositeBakedModel.

  `models` is the mutable baking-result registry map (loader event payload).
  `overrides-fn` takes the flat base model and the world model and returns the
  ItemOverrides the composite reports, keeping energy tiers on the 3D mesh."
  [^Map models overrides-fn]
  (let [mod-id (str modid/mod-id)]
    (doseq [item-id (obj-3d-item-ids)]
      (let [basename (item-id->basename item-id)
            base-mrl (inventory-mrl mod-id basename)
            world-mrl (inventory-mrl mod-id (str basename "_3d"))
            ^BakedModel flat-base (.get models base-mrl)
            ^BakedModel world-model (.get models world-mrl)]
        (if (and flat-base world-model)
          (do
            (.put models base-mrl
                  (ObjCompositeBakedModel. flat-base world-model
                                           (overrides-fn flat-base world-model)))
            (log/debug "[obj-model-baking] composite installed for" item-id))
          (log/debug "[obj-model-baking] skipping" item-id
                     "- flat-base?" (boolean flat-base)
                     "world-model?" (boolean world-model)))))))
