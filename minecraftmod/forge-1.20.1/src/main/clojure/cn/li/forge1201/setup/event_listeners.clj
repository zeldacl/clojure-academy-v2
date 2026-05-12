(ns cn.li.forge1201.setup.event-listeners
  "Forge common EventBus listener registration for gameplay events."
  (:require [cn.li.forge1201.integration.events :as events])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]))

(defn register-common-event-listeners!
  []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false net.minecraftforge.event.entity.player.PlayerInteractEvent$LeftClickBlock
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-left-click-block-event evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false net.minecraftforge.event.level.BlockEvent$EntityPlaceEvent
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-block-place-event evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false net.minecraftforge.event.level.BlockEvent$BreakEvent
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-block-break-event evt))))
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false net.minecraftforge.event.LootTableLoadEvent
                (reify java.util.function.Consumer
                  (accept [_ evt]
                    (events/handle-loot-table-load evt))))
  nil)
