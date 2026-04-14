(ns cn.li.forge1201.platform-bindings
	"Forge-specific static function bindings for core platform hooks.

	These functions use direct typed calls so Loom remapping remains valid in packaged jars."
	(:require [cn.li.mcmod.platform.nbt :as nbt]
					[cn.li.mcmod.platform.position :as pos]
					[cn.li.mcmod.platform.be :as pbe]
					[cn.li.mcmod.registry.metadata :as registry-metadata])
	(:import [net.minecraft.nbt CompoundTag ListTag]
				 [net.minecraft.core BlockPos]
				 [net.minecraft.world.level Level]
				 [net.minecraft.world.level.block Block]
				 [cn.li.forge1201.block.entity ScriptedBlockEntity]))

(extend-type CompoundTag
	nbt/INBTCompound
	(nbt-set-int! [this key value] (.putInt this key (int value)) this)
	(nbt-get-int [this key] (.getInt this key))
	(nbt-set-string! [this key value] (.putString this key (str value)) this)
	(nbt-get-string [this key] (.getString this key))
	(nbt-set-boolean! [this key value] (.putBoolean this key (boolean value)) this)
	(nbt-get-boolean [this key] (.getBoolean this key))
	(nbt-set-double! [this key value] (.putDouble this key (double value)) this)
	(nbt-get-double [this key] (.getDouble this key))
	(nbt-set-tag! [this key tag] (.put this key tag) this)
	(nbt-get-tag [this key] (.get this key))
	(nbt-get-compound [this key] (.getCompound this key))
	(nbt-get-list [this key] (.getList this key 10))
	(nbt-has-key? [this key] (.contains this key))
	(nbt-set-float! [this key value] (.putFloat this key (float value)) this)
	(nbt-get-float [this key] (.getFloat this key))
	(nbt-set-long! [this key value] (.putLong this key (long value)) this)
	(nbt-get-long [this key] (.getLong this key)))

(extend-type ListTag
	nbt/INBTList
	(nbt-append! [this element] (.add this element) this)
	(nbt-list-size [this] (.size this))
	(nbt-list-get [^ListTag this index]
		(let [i (int index)]
		  (when (and (>= i 0) (< i (.size this)))
		    (.get this ^int i))))
	(nbt-list-get-compound [^ListTag this index]
		(let [i (int index)]
		  (when (and (>= i 0) (< i (.size this)))
		    (.getCompound this ^int i)))))

(extend-type BlockPos
	pos/IBlockPos
	(pos-x [this] (.getX this))
	(pos-y [this] (.getY this))
	(pos-z [this] (.getZ this)))

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

(defn nbt-has-key?
	[^CompoundTag nbt ^String key]
	(.contains nbt key))

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
					get-registered-block (requiring-resolve 'cn.li.forge1201.mod/get-registered-block)
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

(defn create-nbt-compound []
	(CompoundTag.))

(defn create-nbt-list []
	(ListTag.))

(defn create-block-pos
	[x y z]
	(BlockPos. (int x) (int y) (int z)))

(defn pos-above
	[^BlockPos p]
	(.above p))