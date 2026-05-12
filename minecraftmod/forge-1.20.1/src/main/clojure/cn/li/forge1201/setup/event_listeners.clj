(ns cn.li.forge1201.setup.event-listeners
  "Forge common EventBus listener registration for gameplay events."
  (:require [cn.li.forge1201.integration.events :as events]
            [cn.li.forge1201.setup.consumer-support :as consumer-support])
  (:import [net.minecraftforge.common MinecraftForge]))

(defn- add-listener!
  [listener-class f]
  (consumer-support/add-normal-listener! (MinecraftForge/EVENT_BUS) listener-class f))

(defn register-common-event-listeners!
  []
  (add-listener! net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock
                 events/handle-right-click-event)
  (add-listener! net.minecraftforge.event.entity.player.PlayerInteractEvent$LeftClickBlock
                 events/handle-left-click-block-event)
  (add-listener! net.minecraftforge.event.level.BlockEvent$EntityPlaceEvent
                 events/handle-block-place-event)
  (add-listener! net.minecraftforge.event.level.BlockEvent$BreakEvent
                 events/handle-block-break-event)
  (add-listener! net.minecraftforge.event.LootTableLoadEvent
                 events/handle-loot-table-load)
  nil)
