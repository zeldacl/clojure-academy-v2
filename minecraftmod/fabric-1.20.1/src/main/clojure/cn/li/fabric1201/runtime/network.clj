(ns cn.li.fabric1201.runtime.network
  "Fabric transport wiring for runtime system.

  Reuses existing runtime RPC handlers and Fabric GUI S2C transport.
  Keeps protocol/message IDs aligned with Forge runtime network implementation."
  (:require [cn.li.fabric1201.gui.network :as gui-network]
            [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.network-core :as network-core]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.runtime.catalog :as runtime-catalog]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]))

(defn send-to-server!
  "Client helper for runtime requests."
  [msg-id payload]
  (net-client/send-to-server msg-id payload))

(defn- find-player-by-uuid
  [uuid-str]
  (query-core/get-player-by-uuid (server-context/get-server) uuid-str))

(defn send-sync-to-client!
  [uuid payload]
  (when-let [^ServerPlayer player (find-player-by-uuid uuid)]
    (gui-network/send-push-to-client! player runtime-catalog/MSG-SYNC-RUNTIME {:uuid uuid :ability-data (:ability-data payload)})
    (gui-network/send-push-to-client! player runtime-catalog/MSG-SYNC-RESOURCE {:uuid uuid :resource-data (:resource-data payload)})
    (gui-network/send-push-to-client! player runtime-catalog/MSG-SYNC-COOLDOWN {:uuid uuid :cooldown-data (:cooldown-data payload)})
    (gui-network/send-push-to-client! player runtime-catalog/MSG-SYNC-PRESET {:uuid uuid :preset-data (:preset-data payload)})))

(defn- send-to-client!
  [uuid msg-id payload]
  (when-let [^ServerPlayer player (find-player-by-uuid uuid)]
    (gui-network/send-push-to-client! player msg-id payload)))

(defn init!
  "Initialize runtime network stack: register server handlers and injected send fns."
  []
  (server-context/install-server-context!)
  (network-core/init-runtime-network! {:send-to-server-fn send-to-server!
                                       :send-to-client-fn send-to-client!})
  (log/info "Fabric runtime network initialized"))
