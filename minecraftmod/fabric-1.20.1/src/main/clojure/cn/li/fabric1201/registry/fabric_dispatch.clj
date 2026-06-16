(ns cn.li.fabric1201.registry.fabric-dispatch
  "Fabric 1.20.1 registry dispatch."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.runtime RegistryDispatchShared]
           [net.minecraft.world.item Item]
           [net.minecraft.world.level.block Block]))

(defn register-block
  [block-id ^Block block-instance]
  (log/info "Registering block with Fabric BuiltInRegistries:" block-id)
  (RegistryDispatchShared/registerBlock modid/*mod-id* (str block-id) block-instance))

(defn register-item
  [item-id ^Item item-instance]
  (log/info "Registering item with Fabric BuiltInRegistries:" item-id)
  (RegistryDispatchShared/registerItem modid/*mod-id* (str item-id) item-instance))
