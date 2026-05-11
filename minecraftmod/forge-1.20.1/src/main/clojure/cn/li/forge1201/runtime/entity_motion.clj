(ns cn.li.forge1201.runtime.entity-motion
  "Forge implementation of IEntityMotion protocol."
  (:require [cn.li.mc1201.runtime.entity-motion-core :as core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server ^MinecraftServer []
	(ServerLifecycleHooks/getCurrentServer))

(defn- resolve-entity [world-id entity-uuid]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (some-> (query-core/resolve-level server world-id)
              (query-core/get-entity-by-uuid entity-uuid)))
    (catch Exception e
      (log/warn "Failed to resolve entity:" world-id entity-uuid (ex-message e))
      nil)))

(defn- set-velocity-impl! [world-id entity-uuid x y z]
  (try
    (boolean (core/set-velocity-for-entity! (resolve-entity world-id entity-uuid) x y z))
    (catch Exception e
      (log/warn "Failed to set entity velocity:" (ex-message e))
      false)))

(defn- add-velocity-impl! [world-id entity-uuid x y z]
  (try
    (boolean (core/add-velocity-for-entity! (resolve-entity world-id entity-uuid) x y z))
    (catch Exception e
      (log/warn "Failed to add entity velocity:" (ex-message e))
      false)))
(defn- discard-entity-impl! [world-id entity-uuid]
  (try
    (boolean (core/discard-entity! (resolve-entity world-id entity-uuid)))
    (catch Exception e
      (log/warn "Failed to discard entity:" (ex-message e))
      false)))

(defn- get-velocity-impl [world-id entity-uuid]
  (try
    (core/get-velocity-for-entity (resolve-entity world-id entity-uuid))
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