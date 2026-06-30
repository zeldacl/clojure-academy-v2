(ns cn.li.forge1201.platform.bindings
	"Forge-specific static function bindings for core platform hooks.

	These functions use direct typed calls so Loom remapping remains valid in packaged jars."
	(:require [cn.li.mcmod.platform.position :as pos]
					[cn.li.mcmod.platform.be :as pbe]
					[cn.li.mcmod.protocol.metadata :as registry-metadata]
					[cn.li.mcmod.util.log :as log]
					[cn.li.forge1201.registry.state :as registry-state])
	(:import [net.minecraft.core BlockPos]
				 [net.minecraft.world.level Level]
				 [net.minecraft.world.level.block Block]
				 [cn.li.forge1201.block.entity ScriptedBlockEntity]))

(extend-type ScriptedBlockEntity
	pos/IHasPosition
	(position-get-block-pos [this]
		(.getBlockPos this))
	(position-get-pos [this]
		(.getBlockPos this))
	pbe/IBlockEntity
	(be-get-level [this] (.getLevel this))
	(be-get-world [this] (.getLevel this))
	(be-get-custom-state [this] (.getCustomState this))
	(be-set-custom-state! [this state] (.setCustomState this state))
	(be-get-block-id [this] (.getBlockId this))
	(be-get-tile-id [this] (.getTileId this))
	(be-set-changed! [this] (.setChanged this)))

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

(defn world-get-dimension-id
	[^Level level]
	(str (.location (.dimension level))))

(defn world-server-session-id
  [^Level level]
  (when-let [sid (cn.li.mc1201.runtime.RuntimeAccessShared/getWorldServerSessionId level)]
    [:server sid]))

(defn open-player-menu!
  [^net.minecraft.world.entity.player.Player player factory]
  (.openMenu player factory))

(defn player-persistent-data
  [^net.minecraft.server.level.ServerPlayer player]
  (.getPersistentData player))

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
	(try
		(registry-state/get-registered-block block-id)
		(catch Exception e
			(log/debug "Failed to resolve registered block for" block-id ":" (.getMessage e))
			nil)))
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

(defn be-get-level
	[^ScriptedBlockEntity be]
	(.getLevel be))

(defn be-get-world
	[^ScriptedBlockEntity be]
	(.getLevel be))

(defn be-get-custom-state
	[^ScriptedBlockEntity be]
	(.getCustomState be))

(defn be-set-custom-state!
	[^ScriptedBlockEntity be state]
	(.setCustomState be state))

(defn be-get-block-id
	[^ScriptedBlockEntity be]
	(.getBlockId be))

(defn be-get-tile-id
	[^ScriptedBlockEntity be]
	(.getTileId be))

(defn be-set-changed!
	[^ScriptedBlockEntity be]
	(.setChanged be))

(defn be-get-fluid-height
	"Return fluid surface height (0.0-1.0) at the block entity's position.
	Uses FluidState.getOwnHeight() — the 1.20 API."
	[^ScriptedBlockEntity be]
	(try
		(when-let [^net.minecraft.world.level.Level level (.getLevel be)]
			(let [pos (.getBlockPos be)
				  ^net.minecraft.world.level.block.state.BlockState state (.getBlockState level pos)
				  ^net.minecraft.world.level.material.FluidState fluid-state (.getFluidState state)]
				(if (.isEmpty fluid-state)
					0.0
					(double (.getOwnHeight fluid-state)))))
		(catch Exception _
			0.0)))