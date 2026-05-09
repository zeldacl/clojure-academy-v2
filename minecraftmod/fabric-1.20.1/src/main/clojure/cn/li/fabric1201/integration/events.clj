(ns cn.li.fabric1201.integration.events
  "Fabric 1.20.1 event handlers"
  (:require [cn.li.ac.core :as core]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.interaction-result :as interaction-result]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mc1201.integration.event-feedback :as event-feedback]
            [cn.li.mc1201.integration.event-handlers :as event-handlers]
            [cn.li.fabric1201.gui.registry-impl :as gui-registry-impl])
  (:import [net.minecraft.world InteractionResult]))

(defn- is-gui-result?
  "Fabric predicate to detect GUI opening results"
  [ret]
  (interaction-result/gui-open-result? ret))

(defn- open-gui-for-result
  "Fabric GUI opener from event result"
  [gui-id player world _pos tile-entity]
  (when (and tile-entity (not (.isClientSide world)))
    (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (event-handlers/handle-block-right-click
    event-data
    core/on-block-right-click
    is-gui-result?
    open-gui-for-result
    ""))

(defn handle-use-block
  "Handle Fabric UseBlockCallback event"
  [player world hand hit-result]
  (try
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
        InteractionResult/CONSUME
        InteractionResult/PASS))
    (catch Throwable t
      (log/info "Error handling use block event:" (.getMessage t))
      (.printStackTrace t)
      InteractionResult/PASS)))

(defn handle-block-break
  "Handle Fabric block break callback. Return true to continue vanilla break."
  [world player pos state _be]
  (try
    (let [block (.getBlock state)
          block-id (event-metadata/identify-block-from-full-name (str block))]
      (if-not block-id
        true
        (let [ret (core/on-block-break
                    {:x (.getX pos)
                     :y (.getY pos)
                     :z (.getZ pos)
                     :pos pos
                     :player player
                     :world world
                     :block block
                     :block-id block-id})]
          (not (and (map? ret) (:cancel-break? ret))))))
    (catch Throwable t
      (log/error "Error handling fabric block break:" (.getMessage t))
      true)))

(defn register-events
  "Register Fabric event listeners - placeholder version"
  []
  (log/info "Registering Fabric event listeners (placeholder)...")
  (log/info "Fabric event listeners registered"))
