(ns cn.li.forge1201.runtime.item-handler
  "Item use event handler for runtime-driven items (Forge layer)."
    (:require [cn.li.mc1201.runtime.item-handler-core :as core]
              [cn.li.mcmod.client.platform-bridge :as client-bridge]
              [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickItem]
           [net.minecraftforge.event.entity.living LivingEntityUseItemEvent$Finish]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.world InteractionResult]
           [net.minecraft.world InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]))

(defn- on-item-finish-using
  "Handle finish using item event (e.g. food/charge complete)."
  [^LivingEntityUseItemEvent$Finish event]
  (try
    (let [entity (.getEntity event)]
      (when (instance? Player entity)
        (let [^Player player entity
              stack (.getItem event)
              side (if (.isClientSide (.level player)) :client :server)]
          (core/dispatch-dsl-item-finish-using! player stack side))))
    (catch Exception e
      (log/error "Error handling item finish-using event" e))))

(defn- on-item-use
  "Handle item right-click event."
  [^PlayerInteractEvent$RightClickItem event]
  (try
    (let [^InteractionHand hand (.getHand event)]
      (when (= hand InteractionHand/MAIN_HAND)
        (let [player (.getEntity event)
              stack (.getItemStack event)
              side (if (.isClientSide (.level player)) :client :server)
              {:keys [consume?]} (core/process-item-use!
                                  player
                                  hand
                                  stack
                                  side
                                  {:open-screen-fn (fn [^Player p _player-uuid]
                                                     (client-bridge/open-skill-tree-screen! (.getUUID p)))})]
          (when consume?
            (.setCancellationResult event InteractionResult/CONSUME)
            (.setCanceled event true)))))
    (catch Exception e
      (log/error "Error handling item use event" e))))

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
