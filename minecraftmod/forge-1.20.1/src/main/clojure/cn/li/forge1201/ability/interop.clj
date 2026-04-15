(ns cn.li.forge1201.ability.interop
	"Forge implementation of ability runtime interop bridge."
	(:require [cn.li.mcmod.platform.ability-interop :as interop]
						[cn.li.mcmod.util.log :as log])
	(:import [net.minecraft.server MinecraftServer]
					 [net.minecraft.server.level ServerPlayer ServerLevel]
					 [net.minecraft.resources ResourceLocation]
					 [net.minecraft.core BlockPos]
					 [net.minecraftforge.server ServerLifecycleHooks]
					 [java.util UUID]))

(defn- get-server ^MinecraftServer []
	(ServerLifecycleHooks/getCurrentServer))

(defn- get-player-by-uuid ^ServerPlayer [uuid-str]
	(try
		(when-let [^MinecraftServer server (get-server)]
			(let [uuid (UUID/fromString (str uuid-str))]
				(.getPlayer (.getPlayerList server) uuid)))
		(catch Exception _
			nil)))

(defn- get-level-by-id ^ServerLevel [world-id]
	(try
		(when-let [^MinecraftServer server (get-server)]
			(let [res-loc (ResourceLocation. (str world-id))]
				(.getLevel server res-loc)))
		(catch Exception _
			nil)))

(defn- player-view-impl [player-uuid]
	(when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
		(let [eye (.getEyePosition player)
					look (.getLookAngle player)
					world-id (some-> (.dimension (.level player)) .location str)]
			{:world-id (or world-id "minecraft:overworld")
			 :x (.x eye)
			 :y (.y eye)
			 :z (.z eye)
			 :look-x (.x look)
			 :look-y (.y look)
			 :look-z (.z look)})))

(defn- player-main-hand-item-impl [player-uuid]
	(when-let [^ServerPlayer player (get-player-by-uuid player-uuid)]
		(let [stack (.getMainHandItem player)]
			(when (and stack (not (.isEmpty stack)))
				stack))))

(defn- block-entity-at-impl [world-id x y z]
	(when-let [^ServerLevel level (get-level-by-id world-id)]
		(.getBlockEntity level (BlockPos. (int x) (int y) (int z)))))

(defn forge-ability-interop []
	(reify interop/IAbilityInterop
		(get-player-view [_ player-uuid]
			(player-view-impl player-uuid))
		(get-player-main-hand-item [_ player-uuid]
			(player-main-hand-item-impl player-uuid))
		(get-block-entity-at [_ world-id x y z]
			(block-entity-at-impl world-id x y z))))

(defn install-ability-interop! []
	(alter-var-root #'interop/*ability-interop* (constantly (forge-ability-interop)))
	(log/info "Forge ability interop installed"))