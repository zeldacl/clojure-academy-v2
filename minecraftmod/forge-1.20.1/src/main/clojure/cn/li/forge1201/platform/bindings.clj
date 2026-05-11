(ns cn.li.forge1201.platform.bindings
	"Forge-specific static function bindings for core platform hooks.

	These functions use direct typed calls so Loom remapping remains valid in packaged jars."
	(:require [cn.li.mcmod.platform.position :as pos]
					[cn.li.mcmod.platform.be :as pbe]
					[cn.li.mcmod.registry.metadata :as registry-metadata])
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

(defn world-get-players
	[^Level level]
	(seq (.players level)))

(defn world-is-raining
	[^Level level]
	(.isRaining level))

(defn world-can-see-sky
	[^Level level pos]
	(.canSeeSky level pos))

(defn world-place-block-by-id
	[^Level level block-id ^BlockPos pos flags]
	(try
		(let [dsl-id (str block-id)
					registry-name (or (registry-metadata/get-block-registry-name dsl-id) dsl-id)
					get-registered-block (requiring-resolve 'cn.li.forge1201.registry.state/get-registered-block)
					^Block blk (or (get-registered-block dsl-id)
											(get-registered-block registry-name))]
			(when blk
				(.setBlock level pos (.defaultBlockState blk) (int flags))
				true))
		(catch Exception _
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

(defn be-set-changed!
	[^ScriptedBlockEntity be]
	(.setChanged be))
