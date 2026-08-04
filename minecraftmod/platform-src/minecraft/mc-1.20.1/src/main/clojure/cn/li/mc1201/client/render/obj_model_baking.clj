(ns cn.li.mc1201.client.render.obj-model-baking
  "Loader-neutral OBJ composite item baking.

  Discovers DSL items with `:item-model-3d-obj` and installs
  {@link cn.li.mc1201.client.render.item.ObjCompositeBakedModel}. Loaders only
  register model-bus events and pass the baking registry map."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.mc1201.datagen.item-model-patterns :as model-patterns]
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

  `models` is the mutable baking-result registry map (loader event payload)."
  [^Map models]
  (let [mod-id (str modid/mod-id)]
    (doseq [item-id (obj-3d-item-ids)]
      (let [basename (item-id->basename item-id)
            gui-mrl (inventory-mrl mod-id basename)
            world-mrl (inventory-mrl mod-id (str basename "_3d"))
            ^BakedModel gui-model (.get models gui-mrl)
            ^BakedModel world-model (.get models world-mrl)]
        (if (and gui-model world-model)
          (do
            (.put models gui-mrl (ObjCompositeBakedModel. gui-model world-model))
            (log/debug "[obj-model-baking] composite model installed for" item-id))
          (log/debug "[obj-model-baking] skipping" item-id
                     "- gui-model?" (boolean gui-model)
                     "world-model?" (boolean world-model)))))))
