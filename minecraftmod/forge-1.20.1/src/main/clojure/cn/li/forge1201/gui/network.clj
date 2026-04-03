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
            [clojure.edn :as edn])
  (:import [cn.li.forge1201.network ClojureNetwork]))

;; ---------------------------------------------------------------------------
;; EDN serialization helpers
;; ---------------------------------------------------------------------------

(defn- serialize ^bytes [data]
  (.getBytes (pr-str data) "UTF-8"))

(defn- deserialize [^bytes bs]
  (try
    (edn/read-string (String. bs "UTF-8"))
    (catch Exception e
      (log/error "Failed to deserialize network payload:" (.getMessage e))
      {})))

;; ---------------------------------------------------------------------------
;; Platform multimethod implementation
;; ---------------------------------------------------------------------------

(defmethod net-client/send-request :forge-1.20.1
  [msg-id payload request-id]
  (ClojureNetwork/sendToServer msg-id (int request-id) (serialize payload)))

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
                             (ClojureNetwork/sendToClient
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
          (let [rid (int request-id)
                payload (deserialize response-bytes)]
            (if (neg? rid)
              (net-client/handle-push (:msg-id payload) (:payload payload))
              (net-client/handle-response rid payload))))]

    (ClojureNetwork/init req-handler resp-handler))
  (log/info "Forge 1.20.1 GUI network system initialized"))
