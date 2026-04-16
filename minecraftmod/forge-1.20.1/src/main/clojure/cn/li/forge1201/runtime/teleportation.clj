(ns cn.li.forge1201.runtime.teleportation
  "Forge implementation of ITeleportation protocol."
  (:require [cn.li.mcmod.platform.teleportation :as ptp]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel ServerPlayer]
           [net.minecraft.world.entity Entity LivingEntity]
           [net.minecraft.world.phys Vec3 AABB]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.core BlockPos]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]))


(defn- get-server ^MinecraftServer []
  (ServerLifecycleHooks/getCurrentServer))

(defn- get-player-by-uuid [uuid-str]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (let [uuid (UUID/fromString uuid-str)]
        (.getPlayer (.getPlayerList server) uuid)))
    (catch Exception e
      (log/warn "Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn- get-level ^ServerLevel [world-id]
  (when-let [^MinecraftServer server (get-server)]
    (let [res-loc (ResourceLocation. world-id)]
      (.getLevel server res-loc))))

(defn- teleport-player-impl! [player-uuid world-id x y z]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (when-let [^ServerLevel target-level (get-level world-id)]
        (when (.isPassenger player)
          (.stopRiding player))
        (let [current-level (.level player)]
          (if (= current-level target-level)
            ;; Same dimension - simple teleport
            (do
              (.teleportTo player target-level x y z (.getYRot player) (.getXRot player))
              true)
            ;; Cross-dimension teleport
            (do
              (.teleportTo player target-level x y z (.getYRot player) (.getXRot player))
              true)))))
    (catch Exception e
      (log/warn "Failed to teleport player:" (ex-message e))
      false)))

(defn- entity-small-enough? [^Entity entity]
  (< (* (.getBbWidth entity) (.getBbWidth entity) (.getBbHeight entity)) 80.0))

(defn- teleport-entity-relative!
  [^Entity entity ^ServerLevel current-level ^ServerLevel target-level tx ty tz dx dy dz]
  (try
    (when (.isPassenger entity)
      (.stopRiding entity))
    (if (= current-level target-level)
      (do
        (.teleportTo entity (+ tx dx) (+ ty dy) (+ tz dz))
        true)
      (let [migrated (.changeDimension entity target-level)]
        (if migrated
          (do
            (.teleportTo ^Entity migrated (+ tx dx) (+ ty dy) (+ tz dz))
            true)
          false)))
    (catch Exception e
      (log/debug "Failed to teleport entity:" (ex-message e))
      false)))

(defn- teleport-with-entities-impl! [player-uuid world-id x y z radius]
  (try
    (if-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (if-let [^ServerLevel target-level (get-level world-id)]
        (let [current-level (.level player)
              player-pos (.position player)
              px (.x player-pos)
              py (.y player-pos)
              pz (.z player-pos)
              aabb (AABB. (- px radius) (- py radius) (- pz radius)
                          (+ px radius) (+ py radius) (+ pz radius))
              nearby (->> (.getEntities current-level nil aabb)
                          (filter #(instance? LivingEntity %))
                          (filter #(not= ^Entity % player))
                          (filter entity-small-enough?))
              teleported-count (atom 0)]

          ;; Teleport caster first, then keep nearby entities' relative offsets.
          (when (.isPassenger player)
            (.stopRiding player))
          (.teleportTo player target-level x y z (.getYRot player) (.getXRot player))
          (swap! teleported-count inc)

          (doseq [^Entity entity nearby]
            (let [epos (.position entity)
                  dx (- (.x epos) px)
                  dy (- (.y epos) py)
                  dz (- (.z epos) pz)]
              (when (teleport-entity-relative! entity current-level target-level x y z dx dy dz)
                (swap! teleported-count inc))))

          {:success true :teleported-count @teleported-count})
        {:success false :teleported-count 0})
      {:success false :teleported-count 0})
    (catch Exception e
      (log/warn "Failed to teleport with entities:" (ex-message e))
      {:success false :teleported-count 0})))

(defn- reset-fall-damage-impl! [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (.resetFallDistance player)
      true)
    (catch Exception e
      (log/warn "Failed to reset fall damage:" (ex-message e))
      false)))

(defn- get-player-position-impl [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [pos (.position player)
            level (.level player)
            dimension-key (.dimension level)
            world-id (str (.location dimension-key))]
        {:world-id world-id
         :x (.x pos)
         :y (.y pos)
         :z (.z pos)}))
    (catch Exception e
      (log/warn "Failed to get player position:" (ex-message e))
      nil)))

(defn- get-player-dimension-impl [player-uuid]
  (try
    (when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
      (let [level (.level player)
            dimension-key (.dimension level)]
        (str (.location dimension-key))))
    (catch Exception e
      (log/warn "Failed to get player dimension:" (ex-message e))
      nil)))

(defn forge-teleportation []
  (reify ptp/ITeleportation
    (teleport-player! [_ player-uuid world-id x y z]
      (teleport-player-impl! player-uuid world-id x y z))
    (teleport-with-entities! [_ player-uuid world-id x y z radius]
      (teleport-with-entities-impl! player-uuid world-id x y z radius))
    (reset-fall-damage! [_ player-uuid]
      (reset-fall-damage-impl! player-uuid))
    (get-player-position [_ player-uuid]
      (get-player-position-impl player-uuid))
    (get-player-dimension [_ player-uuid]
      (get-player-dimension-impl player-uuid))))

(defn install-teleportation! []
  (alter-var-root #'ptp/*teleportation*
                  (constantly (forge-teleportation)))
  (log/info "Forge teleportation installed"))
