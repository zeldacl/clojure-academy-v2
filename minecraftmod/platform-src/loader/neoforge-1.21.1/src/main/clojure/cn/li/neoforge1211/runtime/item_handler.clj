(ns cn.li.neoforge1211.runtime.item-handler
  "Forge event handler for item finish-using lifecycle events."
  (:require [cn.li.mc1211.runtime.event.item-use :as item-use]
            [cn.li.neoforge1211.runtime.owner :as runtime-owner]
            [cn.li.mcmod.util.log :as log])
  (:import [net.neoforged.neoforge.event.entity.living LivingEntityUseItemEvent$Finish]
           [net.neoforged.neoforge.common NeoForge]
           [net.neoforged.bus.api EventPriority]
           [net.minecraft.world.entity.player Player]))

(defn- on-item-finish-using
  [^LivingEntityUseItemEvent$Finish event]
  (let [entity (.getEntity event)
        stack (.getItem event)
        side (if (and (instance? Player entity)
                      (.isClientSide (.level ^Player entity)))
               :client
               :server)]
    (if (instance? Player entity)
      (runtime-owner/with-player-owner ^Player entity side
        #(item-use/handle-finish-using! entity stack side "Forge"))
      (item-use/handle-finish-using! entity stack side "Forge"))))

(defn init!
  "Initialize Forge item lifecycle handlers."
  []
  (.addListener (NeoForge/EVENT_BUS)
                EventPriority/NORMAL
                false
                LivingEntityUseItemEvent$Finish
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-item-finish-using evt))))
  (log/info "Forge item lifecycle handler initialized"))
