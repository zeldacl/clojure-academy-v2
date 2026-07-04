(ns cn.li.forge1201.integration.events.world
  "Forge world load/unload event handlers."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.events.world-save-cache :as world-save-cache]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.mcmod.platform.world-owner-key :as wok]
            [cn.li.forge1201.integration.saveddata.world-lifecycle :as wl-saved])
	  (:import [net.minecraft.server.level ServerLevel]
           [net.minecraftforge.event.level LevelEvent$Load LevelEvent$Unload LevelEvent$Save]
           [net.minecraftforge.event TickEvent$LevelTickEvent TickEvent$Phase]))

(declare register-level-for-state-change-hook! unregister-level-for-state-change-hook!)

(defn handle-world-load
  [^LevelEvent$Load evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (log/info "World loaded, dispatching to lifecycle handlers")
        (register-level-for-state-change-hook! level)
        (let [from-storage (wl-saved/load-world-lifecycle-saved-data level)
              from-cache (world-save-cache/consume-saved-data! level)
              saved (or from-storage from-cache)]
          (world-lifecycle/dispatch-world-load level saved))))
    (catch Throwable t
      (log/error "Error handling world load event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-save
  [^LevelEvent$Save evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)
        (let [saved (world-lifecycle/dispatch-world-save level)]
          (wl-saved/save-world-lifecycle-saved-data! level saved)
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
        (unregister-level-for-state-change-hook! level)
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

;; Per-world-key → Forge SavedData mapping. Populated on world load, cleared
;; on unload. Used by on-world-state-changed callback to call setDirty()
;; without reflection or MinecraftServer lookup.
(def ^:private level-saved-data-by-key (atom {}))

(defn- register-level-for-state-change-hook! [^ServerLevel level]
  (let [sd (wl-saved/get-or-create-saved-data level)
        wk (wok/world-key level)]
    ;; Defensive: if the previous unload did not fire (crash, other mod
    ;; interference), the old SavedData reference would be a zombie — still
    ;; held by this atom but no longer managed by the current save cycle.
    ;; Overwrite unconditionally and warn so the zombie is visible in logs.
    (when (contains? @level-saved-data-by-key wk)
      (log/warn "[forge] Stale SavedData mapping detected for" wk
                "— previous world unload may not have fired cleanly, overwriting"))
    (swap! level-saved-data-by-key assoc wk sd)))

(defn- unregister-level-for-state-change-hook! [^ServerLevel level]
  (let [wk (wok/world-key level)]
    (swap! level-saved-data-by-key dissoc wk)))

(defn register-on-world-state-changed!
  "Generic hook: subscribes to all world-state mutations (wireless networks,
  connections, or any future module storing data via world-registry).
  When state changes, calls setDirty() on the Forge WorldLifecycleSavedData
  so the next save cycle picks it up — no LevelEvent$Save timing dependency."
  []
  (world-registry/set-on-world-state-changed-fn!
    (fn [world-key]
      (when-let [sd (get @level-saved-data-by-key world-key)]
        (.setDirty ^cn.li.forge1201.integration.saveddata.WorldLifecycleSavedData sd))))
  (log/info "[forge] on-world-state-changed → SavedData.setDirty() hook registered"))
