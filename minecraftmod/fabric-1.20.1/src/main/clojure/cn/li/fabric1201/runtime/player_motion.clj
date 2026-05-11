(ns cn.li.fabric1201.runtime.player-motion
  "Fabric implementation of IPlayerMotion protocol."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.player-motion-core :as core]
            [cn.li.mcmod.platform.player-motion :as pm]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]))

(defonce ^:private installed? (atom false))

(defn- get-server ^MinecraftServer []
  (server-context/get-server))

(defn- get-player-by-uuid [uuid-str]
  (try
    (query-core/get-player-by-uuid (get-server) uuid-str)
    (catch Exception e
      (log/warn "[fabric] Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn fabric-player-motion []
  (reify pm/IPlayerMotion
    (set-velocity! [_ player-id x y z]
      (boolean (core/set-velocity-for-player! (get-player-by-uuid player-id) x y z)))
    (add-velocity! [_ player-id x y z]
      (boolean (core/add-velocity-for-player! (get-player-by-uuid player-id) x y z)))
    (get-velocity [_ player-id]
      (core/get-velocity-for-player (get-player-by-uuid player-id)))
    (set-on-ground! [_ player-id on-ground?]
      (boolean (core/set-on-ground-for-player! (get-player-by-uuid player-id) on-ground?)))
    (is-on-ground? [_ player-id]
      (core/is-on-ground-for-player? (get-player-by-uuid player-id)))
    (dismount-riding! [_ player-id]
      (boolean (core/dismount-riding-for-player! (get-player-by-uuid player-id))))))

(defn install-player-motion! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric player motion already installed, skipping")
    (do
      (server-context/install-server-context!)
      (alter-var-root #'pm/*player-motion*
                      (constantly (fabric-player-motion)))
      (log/info "Fabric player motion installed"))))
