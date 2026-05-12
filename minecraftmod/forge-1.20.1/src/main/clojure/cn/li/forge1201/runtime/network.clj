(ns cn.li.forge1201.runtime.network
  "Forge transport wiring for runtime system.

  Reuses the existing mcmod RPC channel used by GUI network:
  - server-side handlers are registered by content runtime network handlers
  - client-side requests use mcmod.network.client/send-to-server

  Here we provide helper send-fns for context manager and sync service."
  (:require [cn.li.mc1201.runtime.network-core :as network-core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.network ClojureNetwork]
           [net.minecraftforge.server ServerLifecycleHooks]
           [net.minecraft.server.level ServerPlayer]))

(defn send-to-server!
  "Client helper for runtime requests."
  [msg-id payload]
  (net-client/send-to-server msg-id payload))

(defn- serialize ^bytes [data]
  (.getBytes (pr-str data) "UTF-8"))

(defn- send-push-to-client!
  [^ServerPlayer player msg-id payload]
  (ClojureNetwork/sendToClient player -1 (serialize {:msg-id msg-id :payload payload})))

(defn- find-player-by-uuid
  [uuid-str]
  (query-core/get-player-by-uuid (ServerLifecycleHooks/getCurrentServer) uuid-str))

(def send-sync-to-client!
  (network-core/create-sync-sender find-player-by-uuid send-push-to-client!))

(def ^:private send-to-client!
  (network-core/create-targeted-client-sender find-player-by-uuid send-push-to-client!))

(defn init!
  "Initialize runtime network stack: register server handlers and injected send fns."
  []
  (network-core/init-runtime-network! {:send-to-server-fn send-to-server!
                                       :send-to-client-fn send-to-client!})
  (log/info "Forge runtime network initialized"))
