(ns cn.li.forge1201.runtime.entity-motion
	"Forge implementation of IEntityMotion protocol."
	(:require [cn.li.mcmod.platform.entity-motion :as pem]
						[cn.li.mcmod.util.log :as log])
	(:import [net.minecraft.server MinecraftServer]
					 [net.minecraft.server.level ServerLevel]
					 [net.minecraft.world.entity Entity]
					 [net.minecraft.world.phys Vec3]
					 [net.minecraftforge.server ServerLifecycleHooks]
					 [net.minecraft.resources ResourceLocation]
					 [java.util UUID]))

(defn- get-server ^MinecraftServer []
	(ServerLifecycleHooks/getCurrentServer))

(defn- get-level ^ServerLevel [world-id]
	(try
		(when-let [^MinecraftServer server (get-server)]
			(let [res-loc (ResourceLocation. world-id)]
				(.getLevel server res-loc)))
		(catch Exception e
			(log/warn "Failed to get level:" world-id (ex-message e))
			nil)))

(defn- get-entity-by-uuid [^ServerLevel level uuid-str]
	(try
		(let [uuid (UUID/fromString uuid-str)]
			(.getEntity level uuid))
		(catch Exception e
			(log/warn "Failed to get entity by UUID:" uuid-str (ex-message e))
			nil)))

(defn- set-velocity-impl! [world-id entity-uuid x y z]
	(try
		(when-let [^ServerLevel level (get-level world-id)]
			(when-let [^Entity entity (get-entity-by-uuid level entity-uuid)]
				(.setDeltaMovement entity (double x) (double y) (double z))
				(set! (.-hurtMarked entity) true)
				true))
		(catch Exception e
			(log/warn "Failed to set entity velocity:" (ex-message e))
			false)))

(defn- add-velocity-impl! [world-id entity-uuid x y z]
	(try
		(when-let [^ServerLevel level (get-level world-id)]
			(when-let [^Entity entity (get-entity-by-uuid level entity-uuid)]
				(let [^Vec3 current (.getDeltaMovement entity)]
					(.setDeltaMovement entity
														 (+ (.x current) (double x))
														 (+ (.y current) (double y))
														 (+ (.z current) (double z)))
					(set! (.-hurtMarked entity) true)
					true)))
		(catch Exception e
			(log/warn "Failed to add entity velocity:" (ex-message e))
			false)))

(defn- discard-entity-impl! [world-id entity-uuid]
	(try
		(when-let [^ServerLevel level (get-level world-id)]
			(when-let [^Entity entity (get-entity-by-uuid level entity-uuid)]
				(.discard entity)
				true))
		(catch Exception e
			(log/warn "Failed to discard entity:" (ex-message e))
			false)))

(defn- get-velocity-impl [world-id entity-uuid]
	(try
		(when-let [^ServerLevel level (get-level world-id)]
			(when-let [^Entity entity (get-entity-by-uuid level entity-uuid)]
				(let [^Vec3 vel (.getDeltaMovement entity)]
					{:x (.x vel)
					 :y (.y vel)
					 :z (.z vel)})))
		(catch Exception e
			(log/warn "Failed to get entity velocity:" (ex-message e))
			nil)))

(defn forge-entity-motion []
	(reify pem/IEntityMotion
		(set-velocity! [_ world-id entity-uuid x y z]
			(set-velocity-impl! world-id entity-uuid x y z))
		(add-velocity! [_ world-id entity-uuid x y z]
			(add-velocity-impl! world-id entity-uuid x y z))
		(discard-entity! [_ world-id entity-uuid]
			(discard-entity-impl! world-id entity-uuid))
		(get-velocity [_ world-id entity-uuid]
			(get-velocity-impl world-id entity-uuid))))

(defn install-entity-motion! []
	(alter-var-root #'pem/*entity-motion*
									(constantly (forge-entity-motion)))
	(log/info "Forge entity motion installed"))