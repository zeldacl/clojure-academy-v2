(ns cn.li.mc1201.client.session
  "Client-local owner/session helpers for client-owned runtime state."
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.multiplayer ClientPacketListener]))

(defn client-session-id
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    [:client (System/identityHashCode mc)]))

(defn local-player-uuid
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn current-local-player-owner
  []
  (when-let [session-id (client-session-id)]
    (when-let [player-uuid (local-player-uuid)]
      {:client-session-id session-id
       :player-uuid player-uuid})))

(defn connection-key
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [^ClientPacketListener connection (.getConnection mc)
          player-uuid (local-player-uuid)]
      (when (and connection player-uuid)
        [:connection (System/identityHashCode connection) player-uuid]))))

(defn owner-key
  [owner]
  (let [session-id (:client-session-id owner)
        player-uuid (:player-uuid owner)]
    (when-not session-id
      (throw (ex-info "Client owner requires :client-session-id" {:owner owner})))
    (when-not player-uuid
      (throw (ex-info "Client owner requires :player-uuid" {:owner owner})))
    [session-id (str player-uuid)]))

(defn require-local-player-owner
  []
  (or (current-local-player-owner)
      (throw (ex-info "Client local player owner unavailable"
                      {:client-session-id (client-session-id)
                       :player-uuid (local-player-uuid)}))))