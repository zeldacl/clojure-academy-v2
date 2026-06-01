(ns cn.li.forge1201.integration.saveddata.world-lifecycle
  (:require [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.integration.saveddata WorldLifecycleSavedData]
           [net.minecraft.nbt CompoundTag]
           [net.minecraft.world.level Level]
           [java.util.function Function Supplier]))

(defn- id->key
  [id]
  (cond
    (keyword? id)
    (if-let [ns (namespace id)]
      (str ns "/" (name id))
      (name id))
    :else
    (str id)))

(defn- key->id
  [^String k]
  (try
    (keyword k)
    (catch Exception _
      k)))

(defn- get-or-create-saved-data
  ^WorldLifecycleSavedData
  [^Level level]
  (let [storage (.getDataStorage level)
        loader (reify Function
                 (apply [_ tag] (WorldLifecycleSavedData/load ^CompoundTag tag)))
        creator (reify Supplier
                  (get [_] (WorldLifecycleSavedData.)))]
    (.computeIfAbsent storage loader creator WorldLifecycleSavedData/NAME)))

(defn load-world-lifecycle-saved-data
  "Return saved map of handler-id -> CompoundTag, or nil when empty."
  [^Level level]
  (try
    (let [^WorldLifecycleSavedData sd (get-or-create-saved-data level)
          ^CompoundTag handlers (.getHandlers sd)
          m (into {}
                  (map (fn [^String k]
                         [(key->id k) (.getCompound handlers k)]))
                  (.getAllKeys handlers))]
      (when (seq m) m))
    (catch Throwable t
      (log/error "Failed to load world lifecycle SavedData:" (.getMessage t))
      nil)))

(defn save-world-lifecycle-saved-data!
  "Persist handler-id -> CompoundTag map into SavedData. Returns nil."
  [^Level level saved-map]
  (try
    (let [^WorldLifecycleSavedData sd (get-or-create-saved-data level)
          ^CompoundTag handlers (CompoundTag.)]
      (doseq [[id payload] saved-map]
        (when (instance? CompoundTag payload)
          (.put handlers (id->key id) ^CompoundTag payload)))
      (.setHandlers sd handlers))
    (catch Throwable t
      (log/error "Failed to save world lifecycle SavedData:" (.getMessage t))))
  nil)

