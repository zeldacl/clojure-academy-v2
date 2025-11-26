(ns my-mod.forge1201.events
  "Forge 1.20.1 event handlers"
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.events.metadata :as event-metadata])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z player world block]} event-data
        block-name (str block)
        ;; Identify block ID from Minecraft block name
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "1.20.1 Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Check if this block has a registered right-click handler
    (when (and block-id (event-metadata/has-event-handler? block-id :on-right-click))
      (log/info "Block has registered handler, dispatching...")
      (core/on-block-right-click (assoc event-data :block-id block-id)))))

(defn handle-right-click-event
  "Handle right-click block event directly from Forge event object"
  [^PlayerInteractEvent$RightClickBlock evt]
  (try
    (let [pos (.getPos evt)
          level (.getLevel evt)
          block-state (.getBlockState level pos)]
      (handle-right-click
        {:x (.getX pos)
         :y (.getY pos)
         :z (.getZ pos)
         :player (.getEntity evt)
         :world level
         :block (.getBlock block-state)}))
    (catch Throwable t
      (log/info "Error handling right-click event:" (.getMessage t))
      (.printStackTrace t))))
