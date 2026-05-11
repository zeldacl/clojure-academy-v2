(ns cn.li.fabric1201.runtime.raycast
  "Fabric implementation of IRaycast protocol.

  Uses shared mc1201 RaycastShared geometry/hit logic and Fabric server-context."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.raycast-normalize :as rn]
            [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.runtime RaycastShared]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn- resolve-level ^ServerLevel [world-id]
  (query-core/resolve-level (get-server) world-id))

(defn- get-player-by-uuid [player-uuid]
  (query-core/get-player-by-uuid (get-server) player-uuid))

(defn- raycast-blocks-impl [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (rn/normalize-bridge-map
      (RaycastShared/raycastBlocks
        (resolve-level world-id)
        start-x start-y start-z dir-x dir-y dir-z max-distance))
    (catch Exception e
      (log/warn "[fabric] Failed to raycast blocks:" (ex-message e))
      nil)))

(defn- raycast-entities-impl [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (rn/normalize-bridge-map
      (RaycastShared/raycastEntities
        (resolve-level world-id)
        start-x start-y start-z dir-x dir-y dir-z max-distance))
    (catch Exception e
      (log/warn "[fabric] Failed to raycast entities:" (ex-message e))
      nil)))

(defn- raycast-combined-impl [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (rn/normalize-bridge-map
      (RaycastShared/raycastCombined
        (resolve-level world-id)
        start-x start-y start-z dir-x dir-y dir-z max-distance))
    (catch Exception e
      (log/warn "[fabric] Failed to raycast combined:" (ex-message e))
      nil)))

(defn- get-player-look-vector-impl [player-uuid]
  (try
    (rn/normalize-bridge-map
      (RaycastShared/getPlayerLookVector (get-player-by-uuid player-uuid)))
    (catch Exception e
      (log/warn "[fabric] Failed to get player look vector:" (ex-message e))
      nil)))

(defn- raycast-from-player-impl [player-uuid max-distance living-only?]
  (try
    (rn/normalize-bridge-map
      (RaycastShared/raycastFromPlayer (get-player-by-uuid player-uuid) max-distance living-only?))
    (catch Exception e
      (log/warn "[fabric] Failed to raycast from player:" (ex-message e))
      nil)))

(defn fabric-raycast []
  (reify prc/IRaycast
    (raycast-blocks [_ world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
      (raycast-blocks-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (raycast-entities [_ world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
      (raycast-entities-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (raycast-combined [_ world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
      (raycast-combined-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (get-player-look-vector [_ player-uuid]
      (get-player-look-vector-impl player-uuid))
    (raycast-from-player [_ player-uuid max-distance living-only?]
      (raycast-from-player-impl player-uuid max-distance living-only?))))

(defn install-raycast! []
  (server-context/install-server-context!)
  (alter-var-root #'prc/*raycast*
                  (constantly (fabric-raycast)))
  (log/info "Fabric raycast installed"))
