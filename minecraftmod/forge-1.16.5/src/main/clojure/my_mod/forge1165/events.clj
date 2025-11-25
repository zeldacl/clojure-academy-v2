(ns my-mod.forge1165.events
  "Forge 1.16.5 event handlers"
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.defs :as defs])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z player world block]} event-data
        block-name (str block)]
    (log/info "1.16.5 Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Check if it's our demo block
    (when (and block-name 
               (or (.contains block-name "demo_block")
                   (.contains block-name (str "my_mod:" defs/demo-block-id))))
      (log/info "Demo block detected! Triggering GUI open logic...")
      (core/on-block-right-click event-data))))

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
