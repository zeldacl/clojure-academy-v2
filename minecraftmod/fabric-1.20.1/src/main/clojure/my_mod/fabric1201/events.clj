(ns my-mod.fabric1201.events
  "Fabric 1.20.1 event handlers"
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.events.metadata :as event-metadata]
            [my-mod.fabric1201.gui.registry-impl :as gui-registry-impl]
            [my-mod.wireless.world-data :as wd])
  (:import [net.fabricmc.fabric.api.event.player UseBlockCallback]
           [net.minecraft.world InteractionResult]
           [net.fabricmc.fabric.api.event.lifecycle.v1 ServerWorldEvents]))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z player world block]} event-data
        block-name (str block)
        ;; Identify block ID from Minecraft block name
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "Fabric 1.20.1 Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Check if this block has a registered right-click handler
    (when (and block-id (event-metadata/has-event-handler? block-id :on-right-click))
      (log/info "Block has registered handler, dispatching...")
      (let [ret (core/on-block-right-click (assoc event-data :block-id block-id))]
        (when (and (map? ret) (contains? ret :gui-id) (contains? ret :player) (contains? ret :world) (contains? ret :pos))
          (try
            (let [{:keys [gui-id player world pos]} ret
                  tile-entity (.getBlockEntity world pos)]
              (when (and tile-entity (not (.isClientSide world)))
                (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))
            (catch Exception e
              (log/error "Failed to open GUI from right-click handler:" (.getMessage e)))))
        ret))))

(defn handle-use-block
  "Handle Fabric UseBlockCallback event"
  [player world hand hit-result]
  (try
    (let [pos (.getBlockPos hit-result)
          block-state (.getBlockState world pos)]
      (handle-right-click
        {:x (.getX pos)
         :y (.getY pos)
         :z (.getZ pos)
         :pos pos
         :sneaking (.isShiftKeyDown player)
         :player player
         :world world
         :block (.getBlock block-state)})
      ;; Return PASS to allow other handlers to process
      InteractionResult/PASS)
    (catch Throwable t
      (log/info "Error handling use block event:" (.getMessage t))
      (.printStackTrace t)
      InteractionResult/PASS)))

(defn register-events []
  "Register Fabric event listeners"
  (log/info "Registering Fabric event listeners...")
  
  ;; Register block interaction event
  (.register UseBlockCallback/EVENT
    (reify UseBlockCallback
      (interact [_ player world hand hit-result]
        (handle-use-block player world hand hit-result))))
  
  ;; Register world load event
  (.register ServerWorldEvents/LOAD
    (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Load
      (onServerWorldLoad [_ server world]
        (try
          (log/info "Fabric world load event, initializing wireless system")
          (wd/on-world-load world nil)
          (catch Throwable t
            (log/error "Error handling fabric world load:" (.getMessage t)))))))
  
  ;; Register world save event
  (.register ServerWorldEvents/SAVE
    (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Save
      (onServerWorldSave [_ server world]
        (try
          (log/info "Fabric world save event, preparing wireless data")
          (wd/on-world-save world)
          (catch Throwable t
            (log/error "Error handling fabric world save:" (.getMessage t)))))))
  
  ;; Register world unload event
  (.register ServerWorldEvents/UNLOAD
    (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Unload
      (onServerWorldUnload [_ server world]
        (try
          (log/info "Fabric world unload event, cleaning up wireless system")
          (wd/on-world-unload world)
          (catch Throwable t
            (log/error "Error handling fabric world unload:" (.getMessage t)))))))
  
  (log/info "Fabric event listeners registered"))
