(ns cn.li.fabric1201.runtime.entity-motion
  "Fabric implementation of IEntityMotion protocol."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-motion-core :as core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-motion :as pem]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(defonce ^:private installed? (atom false))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn- resolve-entity [world-id entity-uuid]
  (try
    (when-let [^MinecraftServer server (get-server)]
      (some-> (query-core/resolve-level server world-id)
              (query-core/get-entity-by-uuid entity-uuid)))
    (catch Exception e
      (log/warn "[fabric] Failed to resolve entity:" world-id entity-uuid (ex-message e))
      nil)))

(defn fabric-entity-motion []
  (reify pem/IEntityMotion
    (set-velocity! [_ world-id entity-uuid x y z]
      (boolean (core/set-velocity-for-entity! (resolve-entity world-id entity-uuid) x y z)))
    (add-velocity! [_ world-id entity-uuid x y z]
      (boolean (core/add-velocity-for-entity! (resolve-entity world-id entity-uuid) x y z)))
    (discard-entity! [_ world-id entity-uuid]
      (boolean (core/discard-entity! (resolve-entity world-id entity-uuid))))
    (get-velocity [_ world-id entity-uuid]
      (core/get-velocity-for-entity (resolve-entity world-id entity-uuid)))))

(defn install-entity-motion! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric entity motion already installed, skipping")
    (do
      (server-context/install-server-context!)
      (alter-var-root #'pem/*entity-motion*
                      (constantly (fabric-entity-motion)))
      (log/info "Fabric entity motion installed"))))
