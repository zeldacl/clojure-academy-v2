(ns cn.li.forge1201.runtime.raycast
  "Forge implementation of IRaycast protocol."
  (:require [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.runtime.raycast-normalize :as rn])
  (:import [cn.li.forge1201.runtime RaycastBridge]))


(defn- raycast-blocks-impl [_world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (rn/normalize-bridge-map
      (RaycastBridge/raycastBlocks _world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (catch Exception e
      (log/warn "Failed to raycast blocks:" (ex-message e))
      nil)))

(defn- raycast-entities-impl [_world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (rn/normalize-bridge-map
      (RaycastBridge/raycastEntities _world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (catch Exception e
      (log/warn "Failed to raycast entities:" (ex-message e))
      nil)))

(defn- raycast-combined-impl [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (rn/normalize-bridge-map
      (RaycastBridge/raycastCombined world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (catch Exception e
      (log/warn "Failed to raycast combined:" (ex-message e))
      nil)))

(defn- get-player-look-vector-impl [player-uuid]
  (try
    (rn/normalize-bridge-map (RaycastBridge/getPlayerLookVector player-uuid))
    (catch Exception e
      (log/warn "Failed to get player look vector:" (ex-message e))
      nil)))

(defn- raycast-from-player-impl [player-uuid max-distance living-only?]
  (try
    (rn/normalize-bridge-map (RaycastBridge/raycastFromPlayer player-uuid max-distance living-only?))
    (catch Exception e
      (log/warn "Failed to raycast from player:" (ex-message e))
      nil)))

(defn forge-raycast []
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
  (alter-var-root #'prc/*raycast*
                  (constantly (forge-raycast)))
  (log/info "Forge raycast installed"))
