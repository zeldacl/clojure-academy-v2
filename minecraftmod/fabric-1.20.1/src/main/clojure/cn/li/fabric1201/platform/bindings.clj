(ns cn.li.fabric1201.platform.bindings
  "Fabric-specific static function bindings for core platform hooks."
  (:require [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.be :as pbe]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]
            [cn.li.fabric1201.mod :as fabric-mod])
  (:import [cn.li.fabric1201.block.entity ScriptedBlockEntity]
           [net.minecraft.core BlockPos]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block Block]))

;; extend-type on ScriptedBlockEntity for IBlockEntity removed (defprotocol deleted — Phase 1.2)
;; BE ops now installed as plain function maps via installer_core.clj

(defn world-is-client-side
  [^Level level]
  (.isClientSide level))

(defn world-get-tile-entity
  [^Level level pos]
  (.getBlockEntity level pos))

(defn world-get-block-state
  [^Level level pos]
  (.getBlockState level pos))

(defn world-set-block
  [^Level level pos state flags]
  (.setBlock level pos state (int flags)))

(defn world-remove-block
  [^Level level pos]
  (.destroyBlock level pos false))

(defn world-break-block
  [^Level level pos drop?]
  (.destroyBlock level pos (boolean drop?)))

(defn world-is-chunk-loaded?
  [^Level level chunk-x chunk-z]
  (.hasChunk level (int chunk-x) (int chunk-z)))

(defn world-get-day-time
  [^Level level]
  (.getDayTime level))

(defn world-get-game-time
  [^Level level]
  (.getGameTime level))

(defn world-get-dimension-id
  [^Level level]
  (str (.location (.dimension level))))

(defn world-server-session-id
  [^Level level]
  (when-let [sid (cn.li.mc1201.runtime.RuntimeAccessShared/getWorldServerSessionId level)]
    [:server sid]))

(defn open-player-menu!
  [^net.minecraft.world.entity.player.Player player factory]
  (.openHandledScreen player factory))

(defn player-persistent-data
  [^net.minecraft.server.level.ServerPlayer player]
  (cn.li.fabric1201.runtime.FabricPlayerPersistentData/get player))

(defn world-get-players
  [^Level level]
  (seq (.players level)))

(defn world-is-raining
  [^Level level]
  (.isRaining level))

(defn world-can-see-sky
  [^Level level pos]
  (.canSeeSky level pos))

(defn- block-id-candidates
  [block-id]
  (let [dsl-id (str block-id)
        registry-name (registry-metadata/get-block-registry-name dsl-id)]
    (distinct (cond-> [dsl-id]
                registry-name (conj registry-name)))))

(defn- lookup-registered-block
  [block-id]
  (fabric-mod/get-registered-block block-id))

(defn world-place-block-by-id
  [^Level level block-id ^BlockPos pos flags]
  (try
    (let [^Block blk (some lookup-registered-block (block-id-candidates block-id))]
      (when blk
        (.setBlock level pos (.defaultBlockState blk) (int flags))
        true))
    (catch Exception e
      (log/error "Failed to place block by id" block-id ":" (.getMessage e))
      false)))
