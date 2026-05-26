(ns cn.li.forge1201.integration.events.world
  "Forge world load/unload event handlers." 
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle])
  (:import [net.minecraftforge.event.level LevelEvent$Load LevelEvent$Unload LevelEvent$Save]
           [net.minecraftforge.event TickEvent$LevelTickEvent TickEvent$Phase]
           [net.minecraft.world.level Level]))

(defonce ^:private pending-world-save-data (atom {}))

(defn- require-world-owner-value
  [level label value]
  (if (some? value)
    value
    (throw (ex-info (format "Forge world lifecycle owner requires %s" label)
                    {:level level
                     :required label}))))

(defn- world-id
  [^Level level]
  (require-world-owner-value level ":world-id" (some-> level .dimension .location str)))

(defn- server-session-id
  [^Level level]
  (require-world-owner-value
    level
    ":server-session-id"
    (when-let [server (some-> level .getServer)]
      [:server (System/identityHashCode server)])))

(defn- world-key
  [^Level level]
  [(server-session-id level) (world-id level)])

(defn- consume-saved-data!
  [level]
  (let [wid (world-key level)
        pending (get @pending-world-save-data wid)]
    (swap! pending-world-save-data dissoc wid)
    pending))

(defn handle-world-load
  [^LevelEvent$Load evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (log/info "World loaded, dispatching to lifecycle handlers")
        (world-lifecycle/dispatch-world-load level (consume-saved-data! level))))
    (catch Throwable t
      (log/error "Error handling world load event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-save
  [^LevelEvent$Save evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (let [saved (world-lifecycle/dispatch-world-save level)
            wid (world-key level)]
          (if (seq saved)
            (swap! pending-world-save-data assoc wid saved)
            (swap! pending-world-save-data dissoc wid)))))
    (catch Throwable t
      (log/error "Error handling world save event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-unload
  [^LevelEvent$Unload evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (log/info "World unloading, dispatching to lifecycle handlers")
        (swap! pending-world-save-data dissoc (world-key level))
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
