(ns cn.li.forge1201.ability.network
  "Forge transport wiring for ability system.

  Reuses the existing mcmod RPC channel used by GUI network:
  - server-side handlers are registered in ac/ability/network.clj
  - client-side requests use mcmod.network.client/send-to-server

  Here we provide helper send-fns for context manager and sync service."
  (:require [cn.li.ac.ability.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.network :as ability-net]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.network ClojureNetwork]
           [net.minecraftforge.server ServerLifecycleHooks]
           [java.util UUID]
           [net.minecraft.server.level ServerPlayer]))

(defn send-to-server!
  "Client helper for ability requests."
  [msg-id payload]
  (net-client/send-to-server msg-id payload))

(defn- serialize ^bytes [data]
  (.getBytes (pr-str data) "UTF-8"))

(defn- send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (ClojureNetwork/sendToClient player -1 (serialize {:msg-id msg-id :payload payload})))

(defn- find-player-by-uuid
  [uuid-str]
  (when-let [server (ServerLifecycleHooks/getCurrentServer)]
    (when-let [player-list (.getPlayerList server)]
      (.getPlayer player-list (UUID/fromString uuid-str)))))

(defn send-sync-to-client!
  [uuid payload]
  (when-let [^ServerPlayer player (find-player-by-uuid uuid)]
    (send-push-to-client! player catalog/MSG-SYNC-ABILITY {:uuid uuid :ability-data (:ability-data payload)})
    (send-push-to-client! player catalog/MSG-SYNC-RESOURCE {:uuid uuid :resource-data (:resource-data payload)})
    (send-push-to-client! player catalog/MSG-SYNC-COOLDOWN {:uuid uuid :cooldown-data (:cooldown-data payload)})
    (send-push-to-client! player catalog/MSG-SYNC-PRESET {:uuid uuid :preset-data (:preset-data payload)})))

(defn- send-to-client!
  [uuid msg-id payload]
  (when-let [^ServerPlayer player (find-player-by-uuid uuid)]
    (send-push-to-client! player msg-id payload)))

(defn- send-context-channel-to-server!
  [ctx-id channel payload]
  (send-to-server! catalog/MSG-CTX-CHANNEL
                   {:ctx-id ctx-id :channel channel :payload payload}))

(defn- send-context-channel-to-client!
  [ctx-id channel payload]
  (when-let [ctx-map (ctx/get-context ctx-id)]
    (send-to-client! (:player-uuid ctx-map)
                     catalog/MSG-CTX-CHANNEL
                     {:ctx-id ctx-id :channel channel :payload payload})))

(defn- send-context-channel-to-except-local!
  [_ctx-id _channel _payload]
  ;; Nearby broadcast exclusion is pending platform player-query integration.
  nil)

(defn init!
  "Initialize ability network stack: register server handlers and injected send fns."
  []
  (ability-net/register-handlers!)
  (ctx/register-route-fns! {:to-server send-context-channel-to-server!
                            :to-client send-context-channel-to-client!
                            :to-except-local send-context-channel-to-except-local!})
  (ctx-mgr/register-send-fns! {:to-server send-to-server!
                               :to-client send-to-client!})
  (log/info "Forge ability network initialized"))
