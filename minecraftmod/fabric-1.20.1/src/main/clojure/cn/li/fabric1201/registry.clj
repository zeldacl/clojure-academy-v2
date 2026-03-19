(ns cn.li.fabric1201.registry
  "Fabric 1.20.1 registry implementations"
  (:require [cn.li.mcmod.registry :as registry]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core Registry]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item Item BlockItem CreativeModeTabs]
           [net.minecraft.world.level.block Block Blocks]
           [net.minecraft.world.level.block.state BlockBehaviour]))

(defmethod registry/register-block :fabric-1.20.1
  [_ block-id block-instance]
  (log/info "Registering block with Fabric:" block-id)
  (Registry/register 
    BuiltInRegistries/BLOCK
    (ResourceLocation. "my_mod" block-id)
    block-instance))

(defmethod registry/register-item :fabric-1.20.1
  [_ item-id item-instance]
  (log/info "Registering item with Fabric:" item-id)
  (Registry/register 
    BuiltInRegistries/ITEM
    (ResourceLocation. "my_mod" item-id)
    item-instance))
