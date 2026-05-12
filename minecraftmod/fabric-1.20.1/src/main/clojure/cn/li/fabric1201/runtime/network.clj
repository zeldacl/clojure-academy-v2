(ns cn.li.fabric1201.runtime.network
  "Fabric transport wiring for runtime system.

  Reuses existing runtime RPC handlers and Fabric GUI S2C transport.
  Keeps protocol/message IDs aligned with Forge runtime network implementation."
  (:require [cn.li.fabric1201.gui.network :as gui-network]
            [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.network-core :as network-core]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]))

(defn send-to-server!
  "Client helper for runtime requests."
  [msg-id payload]
  (net-client/send-to-server msg-id payload))

(defn- find-player-by-uuid
  [uuid-str]
  (query-core/get-player-by-uuid (server-context/get-server) uuid-str))

(def send-sync-to-client!
  (network-core/create-sync-sender find-player-by-uuid gui-network/send-push-to-client!))

(def ^:private send-to-client!
  (network-core/create-targeted-client-sender find-player-by-uuid gui-network/send-push-to-client!))

(defn init!
  "Initialize runtime network stack: register server handlers and injected send fns."
  []
  (server-context/install-server-context!)
  (network-core/init-runtime-network! {:send-to-server-fn send-to-server!
                                       :send-to-client-fn send-to-client!})
  (log/info "Fabric runtime network initialized"))
