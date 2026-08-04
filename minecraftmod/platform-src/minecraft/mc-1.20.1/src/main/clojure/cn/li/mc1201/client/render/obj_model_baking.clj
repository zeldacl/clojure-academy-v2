(ns cn.li.mc1201.client.render.obj-model-baking
  "Loader-neutral OBJ composite item baking.

  Discovers DSL items with `:item-model-3d-obj` and installs
  {@link cn.li.mc1201.client.render.item.ObjCompositeBakedModel}. Loaders only
  register model-bus events and pass the baking registry map.

  Energy-tier override models (`_half` / `_full`) are also wrapped so charged
  hand/ground stays on the 3D mesh without a custom ItemOverrides subclass."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.mcbase.datagen.item-model-patterns :as model-patterns]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.resources.model BakedModel ModelResourceLocation]
           [net.minecraft.resources ResourceLocation]
           [java.util Map]
           [cn.li.mc1201.client.render.item ObjCompositeBakedModel]))

(defn- obj-3d-item-ids
  "Item DSL ids that request a 3D world model beside the flat GUI icon."
  []
  (set (filter #(model-patterns/obj-3d-item? (item-dsl/get-item %))
               (item-dsl/list-items))))

(defn- item-id->basename
  [item-id]
  (model-patterns/registry-model-basename item-id))

(defn- inventory-mrl
  [mod-id model-path]
  (ModelResourceLocation. (ResourceLocation. (str mod-id) (str model-path)) "inventory"))

(defn- wrap-composite!
  [^Map models gui-mrl world-mrl item-id label]
  (let [^BakedModel gui-model (.get models gui-mrl)
        ^BakedModel world-model (.get models world-mrl)]
    (if (and gui-model world-model)
      (do
        (.put models gui-mrl (ObjCompositeBakedModel. gui-model world-model))
        (log/debug "[obj-model-baking] composite installed" label "for" item-id)
        true)
      (do
        (log/debug "[obj-model-baking] skipping" label item-id
                   "- gui-model?" (boolean gui-model)
                   "world-model?" (boolean world-model))
        false))))

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

  Also wraps energy-tier override models when present so predicate resolution
  still returns a composite (GUI flat + world OBJ).

  `models` is the mutable baking-result registry map (loader event payload)."
  [^Map models]
  (let [mod-id (str modid/mod-id)]
    (doseq [item-id (obj-3d-item-ids)]
      (let [basename (item-id->basename item-id)
            item-spec (item-dsl/get-item item-id)
            world-mrl (inventory-mrl mod-id (str basename "_3d"))
            base-mrl (inventory-mrl mod-id basename)]
        (wrap-composite! models base-mrl world-mrl item-id "base")
        (when (model-patterns/energy-tier-item? item-spec)
          (let [levels (get-in item-spec [:properties :item-model-energy-levels])
                tier (model-patterns/energy-tier-model-spec item-id levels)]
            (doseq [suffix [(:half-model tier) (:full-model tier)]]
              (when suffix
                (wrap-composite! models
                                 (inventory-mrl mod-id suffix)
                                 world-mrl
                                 item-id
                                 (str "energy:" suffix))))))))))
