(ns cn.li.forge1201.ability.network
  "Forge transport wiring for ability system.

  Reuses the existing mcmod RPC channel used by GUI network:
  - server-side handlers are registered in ac/ability/network.clj
  - client-side requests use mcmod.network.client/send-to-server

  Here we provide helper send-fns for context manager and sync service."
  (:require [cn.li.ac.ability.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.network :as ability-net]
            [cn.li.ac.ability.event :as evt]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.mcmod.util.log :as log]))

(defn send-to-server!
  "Client helper for ability requests."
  [msg-id payload]
  (net-client/send-to-server msg-id payload))

(defn send-sync-to-client!
  "Temporary sync bridge.
  Until dedicated S2C push packets are added, publish a local event so
  client runtime can pull if needed."
  [uuid payload]
  (evt/fire-ability-event! {:type :sync/pending
                            :player-id uuid
                            :payload payload}))

(defn init!
  "Initialize ability network stack: register server handlers and injected send fns."
  []
  (ability-net/register-handlers!)
  (ctx-mgr/register-send-fns! {:to-server send-to-server!
                               :to-client (fn [_uuid _msg-id _payload]
                                            ;; TODO: add true server->client push packet type.
                                            nil)})
  (log/info "Forge ability network initialized"))
