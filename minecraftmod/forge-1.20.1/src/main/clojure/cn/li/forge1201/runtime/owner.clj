(ns cn.li.forge1201.runtime.owner
  "Canonical runtime owner helpers for Forge event boundaries."
  (:require [cn.li.mcmod.hooks.core :as runtime-hooks])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.entity.player Player]))

(defn player-uuid
  [^Player player]
  (some-> player .getUUID str))

(defn server-session-id
  [^ServerPlayer player]
  (when-let [server (.getServer player)]
    [:server (System/identityHashCode server)]))

(defn server-owner
  [^ServerPlayer player]
  (let [session-id (server-session-id player)
        uuid (player-uuid player)]
    (when-not session-id
      (throw (ex-info "Forge server player owner requires server session"
                      {:player player
                       :player-uuid uuid})))
    (when-not uuid
      (throw (ex-info "Forge server player owner requires player UUID"
                      {:player player
                       :server-session-id session-id})))
    {:logical-side :server
     :server-session-id session-id
     :player-uuid uuid}))

(defn client-owner
  [^Player player]
  (let [client-session-id-fn (requiring-resolve 'cn.li.mc1201.client.session/client-session-id)
        session-id (when client-session-id-fn
                     (client-session-id-fn))
        uuid (player-uuid player)]
    (when-not session-id
      (throw (ex-info "Forge client player owner requires client session"
                      {:player player
                       :player-uuid uuid})))
    (when-not uuid
      (throw (ex-info "Forge client player owner requires player UUID"
                      {:player player
                       :client-session-id session-id})))
    {:logical-side :client
     :client-session-id session-id
     :player-uuid uuid}))

(defn owner-for-player
  [^Player player side]
  (case side
    :server (server-owner ^ServerPlayer player)
    :client (client-owner player)
    (throw (ex-info "Forge player owner requires logical side"
                    {:player player
                     :side side}))))

(defn with-player-owner
  [^Player player side f]
  (let [owner (owner-for-player player side)]
    (if (= :client (:logical-side owner))
      (let [with-bound-client-owner-fn (requiring-resolve 'cn.li.mc1201.client.session/with-bound-client-owner)]
        (when-not with-bound-client-owner-fn
          (throw (ex-info "Forge client owner binding unavailable"
                          {:owner owner})))
        (with-bound-client-owner-fn owner f))
      (runtime-hooks/with-player-state-owner owner
        (f)))))
