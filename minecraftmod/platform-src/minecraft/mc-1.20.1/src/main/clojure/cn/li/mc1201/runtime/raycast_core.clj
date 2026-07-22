(ns cn.li.mc1201.runtime.raycast-core
  "Loader-agnostic IRaycast adapter built on shared RaycastShared helpers."
  (:require [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.raycast-normalize :as rn]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.runtime RaycastShared]
           [net.minecraft.server MinecraftServer]))

(defn- resolve-level [^MinecraftServer server world-id]
  (query-core/resolve-level server world-id))

(defn create-raycast
  "Create an IRaycast adapter using a platform-provided server supplier."
  [get-server]
  {:raycast-blocks (fn [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
                     (try
                       (rn/normalize-bridge-map
                         (RaycastShared/raycastBlocks
                           (resolve-level (get-server) world-id)
                           start-x start-y start-z dir-x dir-y dir-z max-distance))
                       (catch Exception e
                         (log/warn "Failed to raycast blocks:" (ex-message e))
                         nil)))
   :raycast-entities (fn [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
                       (try
                         (rn/normalize-bridge-map
                           (RaycastShared/raycastEntities
                             (resolve-level (get-server) world-id)
                             start-x start-y start-z dir-x dir-y dir-z max-distance))
                         (catch Exception e
                           (log/warn "Failed to raycast entities:" (ex-message e))
                           nil)))
   :raycast-combined (fn [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
                       (let [^MinecraftServer srv (get-server)
                             level (resolve-level srv world-id)
                             raw   (when level
                                     (RaycastShared/raycastCombined
                                       level start-x start-y start-z dir-x dir-y dir-z max-distance))]
                         (let [norm (try (rn/normalize-bridge-map raw) (catch Exception _ nil))]
                           (log/info "[RC-DIAG] raycast" {:server? (some? srv) :level? (some? level) :raw? (some? raw) :norm? (some? norm) :raw-keys (when raw (keys raw)) :norm-keys (when norm (keys norm)) :hit-type-raw (get raw "hit-type") :hit-type-norm (:hit-type norm)})
                           norm)))
   :get-player-look-vector (fn [player-uuid]
                             (try
                               (rn/normalize-bridge-map
                                 (RaycastShared/getPlayerLookVector (query-core/get-player-by-uuid (get-server) player-uuid)))
                               (catch Exception e
                                 (log/warn "Failed to get player look vector:" (ex-message e))
                                 nil)))
   :raycast-from-player (fn [player-uuid max-distance living-only?]
                          (try
                            (rn/normalize-bridge-map
                              (RaycastShared/raycastFromPlayer (query-core/get-player-by-uuid (get-server) player-uuid) max-distance living-only?))
                            (catch Exception e
                              (log/warn "Failed to raycast from player:" (ex-message e))
                              nil)))})