(ns cn.li.forge1201.integration.events.world
  "Forge world load/unload event handlers." 
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.events.world-save-cache :as world-save-cache])
  (:import [net.minecraftforge.event.level LevelEvent$Load LevelEvent$Unload LevelEvent$Save]
           [net.minecraftforge.event TickEvent$LevelTickEvent TickEvent$Phase]))

(defn handle-world-load
  [^LevelEvent$Load evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (log/info "World loaded, dispatching to lifecycle handlers")
        (world-lifecycle/dispatch-world-load level (world-save-cache/consume-saved-data! level))))
    (catch Throwable t
      (log/error "Error handling world load event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-save
  [^LevelEvent$Save evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (let [saved (world-lifecycle/dispatch-world-save level)]
          (world-save-cache/remember-saved-data! level saved))))
    (catch Throwable t
      (log/error "Error handling world save event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-unload
  [^LevelEvent$Unload evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (log/info "World unloading, dispatching to lifecycle handlers")
        (world-save-cache/clear-world-saved-data! level)
        (world-lifecycle/dispatch-world-unload level)))
    (catch Throwable t
      (log/error "Error handling world unload event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-tick
  [^TickEvent$LevelTickEvent evt]
  (try
    (let [level (.level evt)]
      (when (and (= TickEvent$Phase/END (.phase evt))
                 level
                 (not (.isClientSide level)))
        (world-lifecycle/dispatch-world-tick level)))
    (catch Throwable t
      (log/error "Error handling world tick event:" (.getMessage t))
      (.printStackTrace t))))
