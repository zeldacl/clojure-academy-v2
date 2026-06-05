(ns cn.li.forge1201.gui.network
  "Forge 1.20.1 GUI Network Packet System.

  Registers a SimpleChannel (via ClojureNetwork Java bridge) that carries
  two packet types:
    C2SPacket (0) – client → server RPC request
    S2CPacket (1) – server → client RPC response

  Payload/response are EDN-serialized Clojure maps so that arbitrary data
  (keywords, numbers, strings, vectors) is preserved round-trip.

  Also extends the cn.li.mcmod.network.client/send-request multimethod for the
  :forge-1.20.1 dispatch value so the GUI's send-to-server calls work."
  (:require [cn.li.forge1201.gui.block-sync-broadcast]
            [cn.li.mcmod.gui.sync-api :as gui-sync-api]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.gui.network.packet :as packet-base])
  (:import [cn.li.forge1201.network ClojureNetwork]
           [net.minecraft.server.level ServerPlayer]
           [clojure.lang IFn]))

(defn- payload-player-uuid
  [payload]
  (some-> (or (:uuid payload)
              (:player-uuid payload)
              (get-in payload [:payload :uuid])
              (get-in payload [:payload :player-uuid]))
          str))

(defn- with-client-response-owner
  [payload f]
  (let [client-session-id-fn (requiring-resolve 'cn.li.mc1201.client.session/client-session-id)
        local-player-uuid-fn (requiring-resolve 'cn.li.mc1201.client.session/local-player-uuid)
        with-bound-client-owner-fn (requiring-resolve 'cn.li.mc1201.client.session/with-bound-client-owner)
        session-id (when client-session-id-fn
                     (client-session-id-fn))
        ;; Response payloads rarely carry :player-uuid; fall back to the local
        ;; Minecraft player so require-client-owner validation passes during dispatch.
        player-uuid (or (payload-player-uuid payload)
                        (when local-player-uuid-fn
                          (try (local-player-uuid-fn) (catch Exception _ nil))))]
    (when-not with-bound-client-owner-fn
      (throw (ex-info "Client GUI network response owner binding unavailable"
                      {:payload payload})))
    (when-not session-id
      (throw (ex-info "Client GUI network response requires bound client session"
                      {:payload payload})))
    (with-bound-client-owner-fn
     (cond-> {:logical-side :client :client-session-id session-id}
       player-uuid (assoc :player-uuid player-uuid))
     f)))

(defn- server-player-owner
  [^ServerPlayer player]
  {:logical-side :server
   :server-session-id (when-let [server (.getServer player)]
                        [:server (System/identityHashCode server)])
   :player-uuid (str (.getUUID player))})

(defn- with-server-player-owner
  [^ServerPlayer player f]
  (binding [runtime-hooks/*player-state-owner* (server-player-owner player)]
    (f)))

;; ---------------------------------------------------------------------------
;; Platform multimethod implementation
;; ---------------------------------------------------------------------------

(defn- invoke-network-static
  [method-name & args]
  (case method-name
    "sendToServer"
    (let [[msg-id request-id payload] args]
      (ClojureNetwork/sendToServer ^String msg-id (int request-id) ^bytes payload))

    "sendToClient"
    (let [[player req-id response] args]
      (ClojureNetwork/sendToClient ^ServerPlayer player (int req-id) ^bytes response))

    "init"
    (let [[req-handler resp-handler] args]
      (ClojureNetwork/init ^IFn req-handler ^IFn resp-handler))

    (throw (IllegalArgumentException.
             (str "Unknown ClojureNetwork method: " method-name)))))

(defmethod net-client/send-request :forge-1.20.1
  [msg-id payload request-id]
  (invoke-network-static "sendToServer" msg-id (int request-id) (packet-base/encode-payload-bytes payload)))

;; ---------------------------------------------------------------------------
;; Initialization
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize the Forge 1.20.1 SimpleChannel and register packet handlers.
  Called during common mod setup from forge1201.gui.init/init-common!."
  []
  (let [req-handler
        (fn [msg-id request-id payload-bytes player]
          (let [payload (packet-base/decode-payload-bytes
                          payload-bytes
                          #(log/error "Failed to deserialize Forge request payload:" (ex-message %)))
                respond-fn (fn [req-id response]
                             (invoke-network-static "sendToClient"
                               player
                               (int req-id)
                               (packet-base/encode-payload-bytes response)))]
            (with-server-player-owner
              player
              #(net-server/handle-request
                 msg-id
                 (int request-id)
                 payload
                 player
                 respond-fn))))

        resp-handler
        (fn [request-id response-bytes]
          (let [payload (packet-base/decode-payload-bytes
                          response-bytes
                          #(log/error "Failed to deserialize Forge response payload:" (ex-message %)))]
            (with-client-response-owner payload
              #(packet-base/dispatch-client-response!
                 runtime-hooks/*player-state-owner*
                 request-id
                 payload))))]

    (invoke-network-static "init" req-handler resp-handler))
  (gui-sync-api/assert-gui-broadcast-dispatch! :forge-1.20.1)
  (log/info "Forge 1.20.1 GUI network system initialized"))
