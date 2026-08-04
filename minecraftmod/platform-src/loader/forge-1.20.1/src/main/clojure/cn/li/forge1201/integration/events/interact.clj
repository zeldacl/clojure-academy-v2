(ns cn.li.forge1201.integration.events.interact
  "Forge right/left click interaction event handlers."
  (:require [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.events.interaction-result :as interaction-result]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.integration.event-handlers :as event-handlers]
            [cn.li.forge1201.integration.events.event-apply :as event-apply]
            [cn.li.forge1201.integration.events.gui-open-port :as gui-open-port]
            [cn.li.forge1201.runtime.owner :as runtime-owner])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock
            PlayerInteractEvent$LeftClickBlock
            PlayerInteractEvent$EntityInteract
            AttackEntityEvent]
           [net.minecraft.world InteractionHand]))

(defn- is-gui-result?
  [ret]
  (and (map? ret) (contains? ret :gui-id) (contains? ret :player)
       (contains? ret :world) (contains? ret :pos)))

(defn handle-right-click
  [event-data]
  (event-handlers/handle-block-right-click
    event-data
    dispatcher/on-block-right-click
    is-gui-result?
    gui-open-port/open-gui-for-result
    "[RIGHT-CLICK]"))

(defn handle-right-click-event
  [^PlayerInteractEvent$RightClickBlock evt]
  (try
    (let [pos (.getPos evt)
          level (.getLevel evt)
          player (.getEntity evt)
          hand (.getHand evt)]
      (when (= hand InteractionHand/MAIN_HAND)
        (let [block-state (.getBlockState level pos)
              item-stack (.getItemInHand player hand)
                ret (runtime-owner/with-player-owner player (if (.isClientSide level) :client :server)
                  #(handle-right-click
                    {:x (.getX pos)
                     :y (.getY pos)
                     :z (.getZ pos)
                     :pos pos
                     :sneaking (.isShiftKeyDown player)
                     :player player
                     :hand hand
                     :item-stack item-stack
                     :world level
                     :block (.getBlock block-state)}))]
          (cond
            (event-handlers/runtime-active-result? ret)
            (do
              (event-apply/deny-right-click-use! evt)
              (when-not (.isClientSide level)
                (event-apply/cancel-player-interact-fail! evt)))

            (interaction-result/interaction-consumed? ret)
            (do
              (when-not (.isClientSide level)
                (log/info "[FORGE-RIGHT-CLICK-EVENT] pos=" pos "player=" (.getGameProfile player)
                          "block=" (.getBlock block-state)))
              (event-apply/apply-consumed-right-click! evt (.isClientSide level)))))))
    (catch Throwable t
      (log/error "[FORGE-RIGHT-CLICK-EVENT] EXCEPTION:" (ex-message t))
      (log/error "[FORGE-RIGHT-CLICK-EVENT] Stack trace:" t))))

(defn handle-left-click-block-event
  [^PlayerInteractEvent$LeftClickBlock evt]
  (try
    (let [player (.getEntity evt)
          hand (.getHand evt)]
      (when (= hand InteractionHand/MAIN_HAND)
        (when (event-handlers/runtime-active-result?
          (runtime-owner/with-player-owner player (if (.isClientSide (.level player)) :client :server)
            #(event-handlers/handle-block-left-click {:player player})))
          (event-apply/cancel-event! evt))))
    (catch Throwable t
      (log/error "[FORGE-LEFT-CLICK-BLOCK-EVENT] EXCEPTION:" (ex-message t))
      (log/error "[FORGE-LEFT-CLICK-BLOCK-EVENT] Stack trace:" t))))

(defn handle-attack-entity-event
  [^AttackEntityEvent evt]
  (try
    (let [player (.getEntity evt)]
      (when (event-handlers/runtime-active-result?
              (runtime-owner/with-player-owner player (if (.isClientSide (.level player)) :client :server)
                #(event-handlers/handle-entity-attack {:player player})))
        (event-apply/cancel-event! evt)))
    (catch Throwable t
      (log/error "[FORGE-ATTACK-ENTITY-EVENT] EXCEPTION:" (ex-message t))
      (log/error "[FORGE-ATTACK-ENTITY-EVENT] Stack trace:" t))))

(defn handle-entity-interact-event
  [^PlayerInteractEvent$EntityInteract evt]
  (try
    (let [player (.getEntity evt)
          hand (.getHand evt)]
      (when (= hand InteractionHand/MAIN_HAND)
        (when (event-handlers/runtime-active-result?
                (runtime-owner/with-player-owner player (if (.isClientSide (.level player)) :client :server)
                  #(event-handlers/handle-entity-interact {:player player})))
          (event-apply/cancel-player-interact-fail! evt))))
    (catch Throwable t
      (log/error "[FORGE-ENTITY-INTERACT-EVENT] EXCEPTION:" (ex-message t))
      (log/error "[FORGE-ENTITY-INTERACT-EVENT] Stack trace:" t))))
