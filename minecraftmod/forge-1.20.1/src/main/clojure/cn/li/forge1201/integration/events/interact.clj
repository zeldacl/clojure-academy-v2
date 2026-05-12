(ns cn.li.forge1201.integration.events.interact
  "Forge right/left click interaction event handlers." 
  (:require [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.events.interaction-result :as interaction-result]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.integration.event-handlers :as event-handlers]
            [cn.li.forge1201.gui.registry-impl :as gui-registry-impl]
            [cn.li.forge1201.integration.events.bridge :as bridge])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock
            PlayerInteractEvent$LeftClickBlock]
           [net.minecraft.world InteractionHand]
           [net.minecraft.world.level Level]))

(defn- is-gui-result?
  [ret]
  (and (map? ret) (contains? ret :gui-id) (contains? ret :player)
       (contains? ret :world) (contains? ret :pos)))

(defn- open-gui-for-result
  [gui-id player world _pos tile-entity]
  (when (and tile-entity (not (.isClientSide ^Level world)))
    (log/info "[RIGHT-CLICK] Opening GUI on server side...")
    (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))

(defn handle-right-click
  [event-data]
  (event-handlers/handle-block-right-click
    event-data
    dispatcher/on-block-right-click
    is-gui-result?
    open-gui-for-result
    "[RIGHT-CLICK]"))

(defn handle-right-click-event
  [^PlayerInteractEvent$RightClickBlock evt]
  (try
    (let [pos (.getPos evt)
          ^Level level (.getLevel evt)
          player (.getEntity evt)
          hand (.getHand evt)]
      (when (= hand InteractionHand/MAIN_HAND)
        (let [player-uuid (str (.getUUID player))
              runtime-activated? (bridge/runtime-activated? player-uuid)]
          (when runtime-activated?
            (bridge/deny-use! evt)
            (when-not (.isClientSide level)
              (bridge/cancel-fail! evt)))

          (when-not runtime-activated?
            (let [block-state (.getBlockState level pos)
                  item-stack (.getItemInHand player hand)
                  ret (handle-right-click
                        {:x (.getX pos)
                         :y (.getY pos)
                         :z (.getZ pos)
                         :pos pos
                         :sneaking (.isShiftKeyDown player)
                         :player player
                         :hand hand
                         :item-stack item-stack
                         :world level
                         :block (.getBlock block-state)})]
              (when (interaction-result/interaction-consumed? ret)
                (if (.isClientSide level)
                  (bridge/deny-use! evt)
                  (do
                    (log/info "[FORGE-RIGHT-CLICK-EVENT] pos=" pos "player=" (.getGameProfile player)
                              "block=" (.getBlock block-state))
                    (bridge/cancel-consume! evt)))))))))
    (catch Throwable t
      (log/error "[FORGE-RIGHT-CLICK-EVENT] EXCEPTION:" (ex-message t))
      (log/error "[FORGE-RIGHT-CLICK-EVENT] Stack trace:" t))))

(defn handle-left-click-block-event
  [^PlayerInteractEvent$LeftClickBlock evt]
  (try
    (let [player (.getEntity evt)
          hand (.getHand evt)]
      (when (= hand InteractionHand/MAIN_HAND)
        (let [player-uuid (str (.getUUID player))
              runtime-activated? (bridge/runtime-activated? player-uuid)]
          (when runtime-activated?
            (bridge/deny-use! evt)
            (.setCanceled evt true)))))
    (catch Throwable t
      (log/error "[FORGE-LEFT-CLICK-BLOCK-EVENT] EXCEPTION:" (ex-message t))
      (log/error "[FORGE-LEFT-CLICK-BLOCK-EVENT] Stack trace:" t))))
