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
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.gui.network-packet-base :as packet-base])
  (:import [cn.li.forge1201.network ClojureNetwork]
           [net.minecraft.server.level ServerPlayer]
           [clojure.lang IFn]))

;; ---------------------------------------------------------------------------
;; EDN serialization helpers (delegates to shared mc1201 edn-state)
;; ---------------------------------------------------------------------------

(defn- serialize ^bytes [data]
  (packet-base/encode-payload-bytes data))

(defn- deserialize [^bytes bs]
  (packet-base/decode-payload-bytes bs
                                    #(log/error "Failed to deserialize network payload:" (ex-message %))))

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
  (invoke-network-static "sendToServer" msg-id (int request-id) (serialize payload)))

;; ---------------------------------------------------------------------------
;; Initialization
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize the Forge 1.20.1 SimpleChannel and register packet handlers.
  Called during common mod setup from forge1201.gui.init/init-common!."
  []
  (let [req-handler
        (fn [msg-id request-id payload-bytes player]
          (let [payload (deserialize payload-bytes)
                respond-fn (fn [req-id response]
                             (invoke-network-static "sendToClient"
                               player
                               (int req-id)
                               (serialize response)))]
            (net-server/handle-request
              msg-id
              (int request-id)
              payload
              player
              respond-fn)))

        resp-handler
        (fn [request-id response-bytes]
          (let [payload (deserialize response-bytes)]
            (packet-base/dispatch-client-response! request-id payload)))]

    (invoke-network-static "init" req-handler resp-handler))
  (log/info "Forge 1.20.1 GUI network system initialized"))
