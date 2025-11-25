(ns my-mod.fabric1201.events
  "Fabric 1.20.1 event handlers"
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.defs :as defs])
  (:import [net.fabricmc.fabric.api.event.player UseBlockCallback]
           [net.minecraft.world InteractionResult]))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z player world block]} event-data
        block-name (str block)]
    (log/info "Fabric 1.20.1 Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Check if it's our demo block
    (when (and block-name 
               (or (.contains block-name "demo_block")
                   (.contains block-name (str "my_mod:" defs/demo-block-id))))
      (log/info "Demo block detected! Triggering GUI open logic...")
      (core/on-block-right-click event-data))))

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
  (.register UseBlockCallback/EVENT
    (reify UseBlockCallback
      (interact [_ player world hand hit-result]
        (handle-use-block player world hand hit-result)))))
