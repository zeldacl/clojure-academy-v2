(ns cn.li.ac.wireless.data.world
  "Wireless world lifecycle and saved-data integration.

  Owns `WiWorldData` load/save/tick/unload only. Reads use `wireless.data.network-lookup`
  or `wireless.service.queries`; writes use `wireless.service.commands`."
  (:require [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.data.persistence :as persistence]
            [cn.li.ac.wireless.data.world-runtime :as runtime]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.util.log :as log]))

(defprotocol IWiSavedData
  (get-wi-data [this] "Get the in-memory WiWorldData from the wrapper")
  (get-wi-nbt [this] "Get the persisted wireless NBT payload from the wrapper"))

(defn create-world-data
  "Create new world data for a world."
  [world]
  (world-registry/create-world-data world))

(defn get-world-data
  "Get world data for a world, creating it if missing."
  [world]
  (world-registry/get-world-data world))

(defn get-world-data-non-create
  "Get world data without creating."
  [world]
  (world-registry/get-world-data-non-create world))

(defn register-world-data!
  "Register a world -> WiWorldData mapping in the registry."
  [world wi-data]
  (world-registry/register-world-data! world wi-data))

(defn remove-world-data!
  "Remove world data (called on world unload)."
  [world]
  (world-registry/remove-world-data! world))

(deftype WiSavedDataWrapper
  [^:volatile-mutable wi-data
   ^:volatile-mutable wi-nbt]
  IWiSavedData
  (get-wi-data [_]
    wi-data)
  (get-wi-nbt [_]
    wi-nbt)
  Object
  (toString [_]
    (str "WiSavedDataWrapper["
         (if wi-data (str (count (world-registry/networks wi-data)) " networks") "serialized")
         "]")))

(defn create-saved-data
  "Create a saved data wrapper for a world."
  [world]
  (WiSavedDataWrapper. (create-world-data world) nil))

(defn get-saved-data-world-data
  "Extract WiWorldData from SavedData wrapper."
  ([saved-data]
   (get-saved-data-world-data nil saved-data))
  ([world saved-data]
   (when saved-data
     (cond
       (and world (satisfies? nbt/INBTCompound saved-data))
       (persistence/world-data-from-nbt world saved-data)

       (satisfies? IWiSavedData saved-data)
       (let [payload (try (get-wi-nbt saved-data) (catch Exception _ nil))]
         (if payload
           (when world (persistence/world-data-from-nbt world payload))
           (try (get-wi-data saved-data) (catch Exception _ nil))))

       :else
       nil))))

(defn on-world-load
  "Called when world loads - restore from saved data."
  [world saved-data]
  (if saved-data
    (if-let [wi-data (get-saved-data-world-data world saved-data)]
      (let [registered (register-world-data! world wi-data)
            net-count (count (world-registry/networks registered))
            conn-count (count (world-registry/connections registered))]
        (log/info "Restored WiWorldData for world from save:"
                  net-count "networks," conn-count "connections")
        (doseq [conn (world-registry/connections registered)]
          (let [gens (node-conn/get-generators conn)
                recs (node-conn/get-receivers conn)]
            (log/info "  Loaded connection: node=" (vb/vblock-to-string (:node conn))
                      "generators=" (count gens) "receivers=" (count recs))
            (doseq [g gens]
              (log/info "    generator:" (vb/vblock-to-string g)))))
        registered)
      (let [fresh (create-world-data world)]
        (log/info "No saved WiWorldData found, created fresh world data")
        (register-world-data! world fresh)))
    (let [fresh (create-world-data world)]
      (log/info "No saved-data provided, created fresh world data")
      (register-world-data! world fresh))))

(defn on-world-save
  "Called before world save - prepare data for serialization."
  [world]
  (if-let [wi-data (get-world-data-non-create world)]
    (let [pre-conn-count (count (world-registry/connections wi-data))]
      ;; Log pre-validation state for diagnostics
      (doseq [conn (world-registry/connections wi-data)]
        (let [recs (node-conn/get-receivers conn)
              gens (node-conn/get-generators conn)]
          (doseq [r recs]
            (log/info "[on-world-save] connection node=" (vb/vblock-to-string (:node conn))
                      "receiver=" (vb/vblock-to-string r)))
          (doseq [g gens]
            (log/info "[on-world-save] connection node=" (vb/vblock-to-string (:node conn))
                      "generator=" (vb/vblock-to-string g)))))
      (runtime/network-impl-validator wi-data)
      (runtime/node-connection-impl-validator wi-data)
      (let [post-conn-count (count (world-registry/connections wi-data))
            nbt-data (persistence/world-data-to-nbt wi-data)]
        (log/info "Prepared WiWorldData for save: connections"
                  pre-conn-count "->" post-conn-count
                  "(after validation), networks"
                  (count (world-registry/networks wi-data)))
        nbt-data))
    nil))

(defn on-world-tick
  "Called each server world tick - advance wireless runtime state."
  [world]
  (when-let [wi-data (get-world-data-non-create world)]
    (runtime/tick-world-data! wi-data)
    nil))

(defn on-world-unload
  "Called when world unloads - cleanup."
  [world]
  (remove-world-data! world)
  (log/info "Cleaned up WiWorldData for unloaded world"))

(defn init-world-data! []
  (log/info "Registering wireless world data lifecycle handlers...")
  (world-lifecycle/register-world-lifecycle-handler!
    {:id :ac/wireless-world-data
     :on-load on-world-load
     :on-unload on-world-unload
     :on-save on-world-save
     :on-tick on-world-tick})
  (log/info "World data system initialized"))
