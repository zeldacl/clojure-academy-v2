(ns cn.li.forge1201.client.obj-model-registration
  "Forge ModelEvent registration only — baking lives in mc1201."
  (:require [cn.li.mc1201.client.render.obj-model-baking :as baking])
  (:import [net.minecraftforge.client.event ModelEvent$RegisterAdditional ModelEvent$ModifyBakingResult]
           [net.minecraft.client.resources.model ModelResourceLocation]
           [cn.li.forge1201.client.render.item ObjCompositeBakedModel]))

(defn register-additional-obj-models!
  "ModelEvent.RegisterAdditional → register `_3d` inventory variants."
  [^ModelEvent$RegisterAdditional event]
  (doseq [^ModelResourceLocation mrl (baking/additional-obj-inventory-model-locations)]
    (.register event mrl)))

(defn replace-obj-composite-models!
  "ModelEvent.ModifyBakingResult → install Forge ObjCompositeBakedModel."
  [^ModelEvent$ModifyBakingResult event]
  (baking/install-obj-composite-models!
    (.getModels event)
    (fn [gui world] (ObjCompositeBakedModel. gui world))))
