(ns cn.li.forge1201.runtime.network
  "Forge transport wiring for runtime system.

  Reuses the existing mcmod RPC channel used by GUI network:
  - server-side handlers are registered by content runtime network handlers
  - client-side requests use mcmod.network.client/send-to-server

  Here we provide helper send-fns for context manager and sync service."
  (:require [cn.li.mc1201.runtime.network-core :as network-core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.runtime.catalog :as runtime-catalog]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.network ClojureNetwork]
           [net.minecraftforge.server ServerLifecycleHooks]
           [net.minecraft.server.level ServerPlayer]))

(def ^:private sync-message-specs
  [{:msg-id runtime-catalog/MSG-SYNC-RUNTIME
    :payload-key :ability-data}
   {:msg-id runtime-catalog/MSG-SYNC-RESOURCE
    :payload-key :resource-data}
   {:msg-id runtime-catalog/MSG-SYNC-COOLDOWN
    :payload-key :cooldown-data}
   {:msg-id runtime-catalog/MSG-SYNC-PRESET
    :payload-key :preset-data}])

(defn send-to-server!
  "Client helper for runtime requests."
  [msg-id payload]
  (net-client/send-to-server msg-id payload))

(defn- serialize ^bytes [data]
  (.getBytes (pr-str data) "UTF-8"))

(defn- send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (ClojureNetwork/sendToClient player -1 (serialize {:msg-id msg-id :payload payload})))

(defn- sync-message-payloads
  [uuid payload]
  (for [{:keys [msg-id payload-key]} sync-message-specs]
    {:msg-id msg-id
     :payload {:uuid uuid
               payload-key (get payload payload-key)}}))

(defn- find-player-by-uuid
  [uuid-str]
  (query-core/get-player-by-uuid (ServerLifecycleHooks/getCurrentServer) uuid-str))

(defn send-sync-to-client!
  [uuid payload]
  (when-let [^ServerPlayer player (find-player-by-uuid uuid)]
    (doseq [{:keys [msg-id payload]} (sync-message-payloads uuid payload)]
      (try
        (send-push-to-client! player msg-id payload)
        (catch Exception e
          (log/error "Failed to send runtime sync message" msg-id "for" uuid ":" (.getMessage e)))))))

(defn- send-to-client!
  [uuid msg-id payload]
  (when-let [^ServerPlayer player (find-player-by-uuid uuid)]
    (send-push-to-client! player msg-id payload)))

(defn init!
  "Initialize runtime network stack: register server handlers and injected send fns."
  []
  (network-core/init-runtime-network! {:send-to-server-fn send-to-server!
                                       :send-to-client-fn send-to-client!})
  (log/info "Forge runtime network initialized"))
