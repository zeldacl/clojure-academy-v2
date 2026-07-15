(ns cn.li.forge1201.client.energy-item-model-properties
  "Registers item model predicates for :item-model-energy-levels items.

  Content items may use NBT-driven energy + item model overrides; predicate id is
   <modid>:energy (see datagen item_model_provider)."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]
            [cn.li.forge1201.registry.state :as registry-state])
  (:import [net.minecraft.resources ResourceLocation]
           [net.minecraft.client.renderer.item ItemProperties]
           [cn.li.forge1201.client EnergyItemPropertyFunction]))

(defn- get-registered-item [item-id]
  (registry-state/get-registered-item item-id))

(defn register!
  "Call from client setup after items are registered."
  []
  (let [pred-rl (ResourceLocation. modid/mod-id "energy")]
    (doseq [item-id (registry-metadata/get-all-item-ids)]
      (when (get-in (registry-metadata/get-item-spec item-id) [:properties :item-model-energy-levels])
        (when-let [item (get-registered-item item-id)]
          (ItemProperties/register item pred-rl EnergyItemPropertyFunction/INSTANCE)
          (log/info "Item model energy predicate registered" {:item-id item-id}))))))
