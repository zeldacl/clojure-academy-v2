(ns cn.li.mc1201.client.energy-item-model-properties
  "Registers item model predicates for :item-model-energy-levels items.

  Predicate id is `<modid>:energy` (see datagen item_model_provider)."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.resources ResourceLocation]
           [net.minecraft.client.renderer.item ItemProperties]
           [net.minecraft.world.item Item]
           [cn.li.mc1201.client.render.item EnergyItemPropertyFunction]
           [cn.li.mc1201.runtime ItemRegistry]))

(defn- resolve-item
  ^Item
  [item-id]
  (ItemRegistry/getItemById
    (modid/namespaced-path (registry-metadata/get-item-registry-name item-id))))

(defn register!
  "Call from client setup after items are registered."
  []
  (let [pred-rl (ResourceLocation. (str modid/mod-id) "energy")]
    (doseq [item-id (registry-metadata/get-all-item-ids)]
      (when (get-in (registry-metadata/get-item-spec item-id) [:properties :item-model-energy-levels])
        (when-let [item (resolve-item item-id)]
          (ItemProperties/register item pred-rl EnergyItemPropertyFunction/INSTANCE)
          (log/info "Item model energy predicate registered" {:item-id item-id}))))))
