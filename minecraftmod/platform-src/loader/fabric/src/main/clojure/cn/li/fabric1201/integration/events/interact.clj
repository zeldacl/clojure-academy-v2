(ns cn.li.fabric1201.integration.events.interact
  "Fabric interaction event handlers extracted from monolithic events namespace."
  (:require [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.interaction-result :as interaction-result]
            [cn.li.mc1201.integration.event-handlers :as event-handlers]
            [cn.li.fabric1201.adapter.gui-registry :as gui-registry-impl])
  (:import [net.minecraft.core BlockPos]
           [net.minecraft.world InteractionHand InteractionResult]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys BlockHitResult]))

(defn- is-gui-result?
  [ret]
  (interaction-result/gui-open-result? ret))

(defn- open-gui-for-result
  [gui-id player ^Level world _pos tile-entity]
  (when (and tile-entity (not (.isClientSide world)))
    (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))

(defn handle-right-click
  [event-data]
  (event-handlers/handle-block-right-click
    event-data
    dispatcher/on-block-right-click
    is-gui-result?
    open-gui-for-result
    ""))

(defn handle-use-block
  [^Player player ^Level world ^InteractionHand hand ^BlockHitResult hit-result]
  (try
    (let [^BlockPos pos (.getBlockPos hit-result)
          ^BlockState block-state (.getBlockState world pos)
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
                 :world world
                 :block (.getBlock block-state)})]
      (cond
        (event-handlers/runtime-active-result? ret) InteractionResult/FAIL
        (interaction-result/interaction-consumed? ret) InteractionResult/SUCCESS
        :else InteractionResult/PASS))
    (catch Throwable t
      (log/info "Error handling use block event:" (.getMessage t))
      (.printStackTrace t)
      InteractionResult/PASS)))

(defn handle-attack-block
  [player _world _hand _pos _direction]
  (try
    (if (event-handlers/runtime-active-result?
          (event-handlers/handle-block-left-click {:player player}))
      InteractionResult/FAIL
      InteractionResult/PASS)
    (catch Throwable t
      (log/error "Error handling fabric attack block:" (.getMessage t))
      InteractionResult/PASS)))

(defn handle-attack-entity
  [player _world _hand _entity _hit-result]
  (try
    (if (event-handlers/runtime-active-result?
          (event-handlers/handle-entity-attack {:player player}))
      InteractionResult/FAIL
      InteractionResult/PASS)
    (catch Throwable t
      (log/error "Error handling fabric attack entity:" (.getMessage t))
      InteractionResult/PASS)))

(defn handle-use-entity
  [player _world _hand _entity _hit-result]
  (try
    (if (event-handlers/runtime-active-result?
          (event-handlers/handle-entity-interact {:player player}))
      InteractionResult/FAIL
      InteractionResult/PASS)
    (catch Throwable t
      (log/error "Error handling fabric use entity:" (.getMessage t))
      InteractionResult/PASS)))
