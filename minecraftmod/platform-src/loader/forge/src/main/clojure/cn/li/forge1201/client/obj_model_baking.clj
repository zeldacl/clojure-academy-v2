(ns cn.li.forge1201.client.obj-model-baking
  "Generic forge:obj model baking — replaces per-item composite BakedModels.

  Iterates the item DSL metadata registry at bake time. Every item with
  {:properties {:item-model-3d-obj ...}} gets a composite ObjCompositeBakedModel
  that switches between the 2D item/generated model (GUI/ground/fixed) and the
  3D forge:obj model (first/third-person handheld).

  Pattern mirrors register-scripted-block-entity-renderers!: a single generic
  Java class (ObjCompositeBakedModel) is driven by Clojure metadata iteration."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.item.dsl :as item-dsl]
            [cn.li.mc1201.datagen.item-model-patterns :as model-patterns]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.resources.model BakedModel ModelResourceLocation]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.client.event ModelEvent$RegisterAdditional ModelEvent$ModifyBakingResult]
           [java.util Map]
           [cn.li.forge1201.client.render.item ObjCompositeBakedModel]))

(defn- obj-3d-item-ids
  "Return the set of item DSL ids that request a forge:obj 3D model."
  []
  (set (filter #(model-patterns/obj-3d-item? (item-dsl/get-item %))
               (item-dsl/list-items))))

(defn- item-id->basename
  [item-id]
  ;; Must match registry-model-basename in item-model-patterns
  (str/replace (str item-id) #"-" "_"))

(defn register-additional-obj-models!
  "ModelEvent.RegisterAdditional handler.
  Registers the _3d variant for every item with :item-model-3d-obj."
  [^ModelEvent$RegisterAdditional event]
  (let [mod-id (str modid/mod-id)]
    (doseq [item-id (obj-3d-item-ids)]
      (let [basename (item-id->basename item-id)
            mrl (ModelResourceLocation.
                  (ResourceLocation. mod-id (str basename "_3d"))
                  "inventory")]
        (.register event mrl)
        (log/debug "[obj-model-baking] registered 3D model for" item-id ":" (str basename "_3d"))))))

(defn replace-obj-composite-models!
  "ModelEvent.ModifyBakingResult handler.
  Replaces each item's baked model with an ObjCompositeBakedModel that
  delegates to the 2D model for GUI/ground and the 3D OBJ model for handheld."
  [^ModelEvent$ModifyBakingResult event]
  (let [^Map registry (.getModels event)
        mod-id (str modid/mod-id)]
    (doseq [item-id (obj-3d-item-ids)]
      (let [basename (item-id->basename item-id)
            gui-mrl (ModelResourceLocation.
                      (ResourceLocation. mod-id basename)
                      "inventory")
            world-mrl (ModelResourceLocation.
                        (ResourceLocation. mod-id (str basename "_3d"))
                        "inventory")
            ^BakedModel gui-model (.get registry gui-mrl)
            ^BakedModel world-model (.get registry world-mrl)]
        (if (and gui-model world-model)
          (do
            (.put registry gui-mrl (ObjCompositeBakedModel. gui-model world-model))
            (log/debug "[obj-model-baking] composite model installed for" item-id))
          (log/debug "[obj-model-baking] skipping" item-id
                     "- gui-model?" (boolean gui-model)
                     "world-model?" (boolean world-model)))))))
