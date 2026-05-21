(ns cn.li.forge1201.setup.event-registration-manifest
  "Declarative event registration manifest for Forge lifecycle/common events.")

(defn lifecycle-listener-specs
  [{:keys [on-common-setup on-client-setup]}]
  [{:listener-class net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent
    :handler on-common-setup}
   {:listener-class net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
    :handler on-client-setup}])

(defn common-event-listener-specs
  []
  [{:listener-class net.minecraftforge.event.entity.player.PlayerInteractEvent$RightClickBlock
    :handler 'cn.li.forge1201.integration.events.interact/handle-right-click-event}
   {:listener-class net.minecraftforge.event.entity.player.PlayerInteractEvent$LeftClickBlock
    :handler 'cn.li.forge1201.integration.events.interact/handle-left-click-block-event}
   {:listener-class net.minecraftforge.event.level.BlockEvent$EntityPlaceEvent
    :handler 'cn.li.forge1201.integration.events.block/handle-block-place-event}
   {:listener-class net.minecraftforge.event.level.BlockEvent$BreakEvent
    :handler 'cn.li.forge1201.integration.events.block/handle-block-break-event}
   {:listener-class net.minecraftforge.event.LootTableLoadEvent
    :handler 'cn.li.forge1201.integration.events.loot/handle-loot-table-load}
     {:listener-class net.minecraftforge.event.level.LevelEvent$Load
    :handler 'cn.li.forge1201.integration.events.world/handle-world-load}
     {:listener-class net.minecraftforge.event.level.LevelEvent$Save
    :handler 'cn.li.forge1201.integration.events.world/handle-world-save}
     {:listener-class net.minecraftforge.event.level.LevelEvent$Unload
    :handler 'cn.li.forge1201.integration.events.world/handle-world-unload}
     {:listener-class net.minecraftforge.event.TickEvent$LevelTickEvent
    :handler 'cn.li.forge1201.integration.events.world/handle-world-tick}])