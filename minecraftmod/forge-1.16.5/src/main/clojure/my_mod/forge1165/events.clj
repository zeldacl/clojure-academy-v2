(ns my-mod.forge1165.events
  "Forge 1.16.5 event handlers"
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.events.metadata :as event-metadata]
            [my-mod.wireless.world-data :as wd])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.event.world WorldEvent$Load WorldEvent$Unload]))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z player world block]} event-data
        block-name (str block)
        ;; Identify block ID from Minecraft block name
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "1.16.5 Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Check if this block has a registered right-click handler
    (when (and block-id (event-metadata/has-event-handler? block-id :on-right-click))
      (log/info "Block has registered handler, dispatching...")
      (core/on-block-right-click (assoc event-data :block-id block-id)))))

(defn handle-right-click-event
  "Handle right-click block event directly from Forge event object"
  [^PlayerInteractEvent$RightClickBlock evt]
  (try
    (let [pos (.getPos evt)
          world (.getWorld evt)
          block-state (.getBlockState world pos)]
      (handle-right-click
        {:x (.getX pos)
         :y (.getY pos)
         :z (.getZ pos)
         :player (.getPlayer evt)
         :world world
         :block (.getBlock block-state)}))
    (catch Throwable t
      (log/info "Error handling right-click event:" (.getMessage t))
      (.printStackTrace t))))

;; ============================================================================
;; World Events (Forge 1.16.5)
;; ============================================================================

(defn handle-world-load
  "Handle world load event - restore wireless network data"
  [^WorldEvent$Load evt]
  (try
    (let [world (.getWorld evt)]
      (when-not (.isRemote world)  ; Server side only
        (log/info "World loaded, initializing wireless system")
        (wd/on-world-load world nil)))
    (catch Throwable t
      (log/error "Error handling world load event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-unload
  "Handle world unload event - cleanup wireless network data"
  [^WorldEvent$Unload evt]
  (try
    (let [world (.getWorld evt)]
      (when-not (.isRemote world)  ; Server side only
        (log/info "World unloading, cleaning up wireless system")
        (wd/on-world-unload world)))
    (catch Throwable t
      (log/error "Error handling world unload event:" (.getMessage t))
      (.printStackTrace t))))
