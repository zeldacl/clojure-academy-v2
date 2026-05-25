(ns cn.li.forge1201.runtime.item-handler
  "Item use event handler for runtime-driven items (Forge layer)."
    (:require [cn.li.mc1201.runtime.event.item-use :as item-use]
              [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickItem]
           [net.minecraftforge.event.entity.living LivingEntityUseItemEvent$Finish]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.world InteractionResult]
           [net.minecraft.world InteractionHand]
           [net.minecraft.world.entity.player Player]))

(defn- on-item-finish-using
  "Handle finish using item event (e.g. food/charge complete)."
  [^LivingEntityUseItemEvent$Finish event]
  (let [entity (.getEntity event)
        stack (.getItem event)
        side (if (and (instance? Player entity)
                      (.isClientSide (.level ^Player entity)))
               :client
               :server)]
      (item-use/handle-finish-using! entity stack side "Forge")))

(defn- on-item-use
  "Handle item right-click event."
  [^PlayerInteractEvent$RightClickItem event]
  (let [^InteractionHand hand (.getHand event)
        player (.getEntity event)
        stack (.getItemStack event)
        side (if (.isClientSide (.level player)) :client :server)
        {:keys [consume?]} (item-use/handle-use
                            player
                            hand
                            stack
                            side
                            {}
                            "Forge")]
    (when consume?
      (.setCancellationResult event InteractionResult/CONSUME)
      (.setCanceled event true))))

(defn init!
  "Initialize item use event handler."
  []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL
                false
                PlayerInteractEvent$RightClickItem
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-item-use evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL
                false
                LivingEntityUseItemEvent$Finish
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-item-finish-using evt))))
  (log/info "Runtime item handler initialized"))
