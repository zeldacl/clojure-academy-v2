(ns cn.li.fabric1201.integration.events.interact
  "Fabric interaction event handlers extracted from monolithic events namespace."
  (:require [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.interaction-result :as interaction-result]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mc1201.integration.event-handlers :as event-handlers]
            [cn.li.fabric1201.adapter.gui-registry :as gui-registry-impl])
  (:import [net.minecraft.world InteractionResult]))

(defn- is-gui-result?
  [ret]
  (interaction-result/gui-open-result? ret))

(defn- open-gui-for-result
  [gui-id player world _pos tile-entity]
  (when (and tile-entity (not (.isClientSide world)))
    (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))

(defn- runtime-activated?
  [player]
  (boolean (get-in (power-runtime/get-player-state (str (.getUUID player)))
                   [:resource-data :activated])))

(defn handle-right-click
  [event-data]
  (event-handlers/handle-block-right-click
    event-data
    dispatcher/on-block-right-click
    is-gui-result?
    open-gui-for-result
    ""))

(defn handle-use-block
  [player world hand hit-result]
  (try
    (if (runtime-activated? player)
      InteractionResult/FAIL
      (let [pos (.getBlockPos hit-result)
            block-state (.getBlockState world pos)
            item-stack (.getStackInHand player hand)
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
        (if (interaction-result/interaction-consumed? ret)
          InteractionResult/SUCCESS
          InteractionResult/PASS)))
    (catch Throwable t
      (log/info "Error handling use block event:" (.getMessage t))
      (.printStackTrace t)
      InteractionResult/PASS)))

(defn handle-attack-block
  [player _world _hand _pos _direction]
  (try
    (if (runtime-activated? player)
      InteractionResult/FAIL
      InteractionResult/PASS)
    (catch Throwable t
      (log/error "Error handling fabric attack block:" (.getMessage t))
      InteractionResult/PASS)))
