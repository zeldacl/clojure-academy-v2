(ns my-mod.wireless.gui.solar-container
  "Solar Generator GUI container (server-side + synced atoms).

  Unlike node/matrix, SolarGen uses a real Java BlockEntity. This container
  talks to it via reflective method calls (getEnergy/getMaxEnergy/getStatusName)
  to keep core platform-neutral."
  (:require [my-mod.util.log :as log]))

(defrecord SolarContainer
  [tile-entity
   player
   energy      ;; atom<double>
   max-energy  ;; atom<double>
   status      ;; atom<string>  \"STOPPED\"|\"WEAK\"|\"STRONG\"
   sync-ticker])

(defn- safe-call
  ([obj method] (safe-call obj method nil))
  ([obj method arg]
   (when obj
     (try
       (if (some? arg)
         (clojure.lang.Reflector/invokeInstanceMethod obj method (object-array [arg]))
         (clojure.lang.Reflector/invokeInstanceMethod obj method (object-array [])))
       (catch Exception e
         (log/debug "SolarContainer: call failed" method ":" (.getMessage e))
         nil)))))

(defn create-container
  "Create Solar Generator container instance.

  tile: platform-specific Java BlockEntity (Forge/Fabric), expected methods:
  - getEnergy() double
  - getMaxEnergy() double
  - getStatusName() String"
  [tile player]
  (->SolarContainer
    tile
    player
    (atom (double (or (safe-call tile "getEnergy") 0.0)))
    (atom (double (or (safe-call tile "getMaxEnergy") 1000.0)))
    (atom (str (or (safe-call tile "getStatusName") "STOPPED")))
    (atom 0)))

(defn still-valid?
  "Best-effort validity check.
  Keep permissive to avoid breaking when player/world APIs vary."
  [_container _player]
  true)

(defn sync-to-client!
  "Update synced atoms from tile entity (server -> client)."
  [container]
  (let [tile (:tile-entity container)]
    (reset! (:energy container) (double (or (safe-call tile "getEnergy") 0.0)))
    (reset! (:max-energy container) (double (or (safe-call tile "getMaxEnergy") 1000.0)))
    (reset! (:status container) (str (or (safe-call tile "getStatusName") "STOPPED")))))

(defn get-sync-data
  [container]
  {:energy @(:energy container)
   :max-energy @(:max-energy container)
   :status @(:status container)})

(defn apply-sync-data!
  [container data]
  (when (contains? data :energy) (reset! (:energy container) (double (:energy data))))
  (when (contains? data :max-energy) (reset! (:max-energy container) (double (:max-energy data))))
  (when (contains? data :status) (reset! (:status container) (str (:status data)))))

(defn tick!
  "Container tick; called by platform menu bridge."
  [container]
  (swap! (:sync-ticker container) inc)
  (sync-to-client! container))

(defn handle-button-click!
  [_container _button-id _player]
  nil)

(defn on-close
  "Cleanup when container is closed."
  [container]
  (reset! (:sync-ticker container) 0))

