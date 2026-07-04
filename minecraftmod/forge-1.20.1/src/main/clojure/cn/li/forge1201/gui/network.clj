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
  (:require [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mc1201.client.session :as mc-session])
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
  (let [session-id (mc-session/client-session-id)
        ;; Response payloads rarely carry :player-uuid; fall back to the local
        ;; Minecraft player so require-client-owner validation passes during dispatch.
        player-uuid (or (payload-player-uuid payload)
                        (try (mc-session/local-player-uuid) (catch Exception _ nil)))]
    (when-not session-id
      (throw (ex-info "Client GUI network response requires bound client session"
                      {:payload payload})))
    (mc-session/with-bound-client-owner
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
  (runtime-hooks/with-client-ctx-fn {:player-owner (server-player-owner player)} f))

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
          (try
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
                   respond-fn)))
            (catch Throwable t
              (log/error "[GUI-NETWORK] req-handler UNCAUGHT:" (ex-message t) (.printStackTrace t)))))

        resp-handler
        (fn [request-id response-bytes]
          (try
            (let [payload (packet-base/decode-payload-bytes
                            response-bytes
                            #(log/error "Failed to deserialize Forge response payload:" (ex-message %)))]
              (with-client-response-owner payload
                #(packet-base/dispatch-client-response!
                   (runtime-hooks/*player-state-owner*)
                   request-id
                   payload)))
            (catch Throwable t
              (log/error "[GUI-NETWORK] resp-handler UNCAUGHT request-id=" request-id ":" (ex-message t) (.printStackTrace t)))))

        ] ;; <-- explicit vector close for let bindings

    (invoke-network-static "init" req-handler resp-handler))
  (log/info "Forge 1.20.1 GUI network system initialized"))
