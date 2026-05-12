(ns cn.li.forge1201.integration.events.world
  "Forge world load/unload event handlers." 
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle])
  (:import [net.minecraftforge.event.level LevelEvent$Load LevelEvent$Unload]))

(defn handle-world-load
  [^LevelEvent$Load evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (log/info "World loaded, dispatching to lifecycle handlers")
        (world-lifecycle/dispatch-world-load level nil)))
    (catch Throwable t
      (log/error "Error handling world load event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-unload
  [^LevelEvent$Unload evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (log/info "World unloading, dispatching to lifecycle handlers")
        (world-lifecycle/dispatch-world-unload level)))
    (catch Throwable t
      (log/error "Error handling world unload event:" (.getMessage t))
      (.printStackTrace t))))
