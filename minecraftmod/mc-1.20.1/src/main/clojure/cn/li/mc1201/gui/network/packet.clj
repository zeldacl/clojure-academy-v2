(ns cn.li.mc1201.gui.network.packet
  "Shared packet model, envelope, and codec helpers for GUI/network transports.

  This namespace is intentionally data-only and loader-agnostic. Forge/Fabric
  transports may read/write bytes or buffers, but request/response/push envelope
  semantics live here."
  (:require [clojure.string :as str]
            [cn.li.mc1201.runtime.edn-state :as es]
            [cn.li.mcmod.network.client :as net-client]))

(defn make-packet
  [packet-id payload]
  {:packet-id (keyword packet-id)
   :payload (or payload {})})

(defn packet-id [packet]
  (:packet-id packet))

(defn payload [packet]
  (:payload packet))

(defn valid-packet?
  [packet]
  (and (map? packet)
       (keyword? (:packet-id packet))
       (map? (:payload packet))))

(defn packet-topic
  "Build a normalized topic string used by platform network adapters."
  [namespace packet]
  (str (str/trim (str namespace)) "/" (name (packet-id packet))))

(defn encode-payload
  [payload]
  (es/encode-edn (or payload {})))

(defn decode-payload
  [s on-error]
  (let [result (es/decode-edn-safe
                (or s "{}")
                on-error)]
    (if (map? result) result {})))

(defn encode-payload-bytes
  [payload]
  (let [^String encoded (str (encode-payload payload))]
    (.getBytes encoded "UTF-8")))

(defn decode-payload-bytes
  [^bytes bs on-error]
  (decode-payload (String. bs "UTF-8") on-error))

(defn normalize-map
  [v]
  (if (map? v) v {}))

(defn request-map
  "Canonical client->server RPC envelope used by string/buffer transports."
  [msg-id request-id payload]
  {:msg-id msg-id
   :request-id (int (or request-id -1))
   :payload (normalize-map payload)})

(defn response-map
  "Canonical server->client response envelope."
  [request-id payload]
  {:request-id (int (or request-id -1))
   :payload (normalize-map payload)})

(defn push-map
  "Canonical server push envelope. request-id -1 is reserved for pushes."
  [msg-id payload]
  (response-map -1 {:msg-id msg-id
                    :payload (normalize-map payload)}))

(defn normalize-request
  "Normalize a decoded client->server RPC envelope."
  [decoded]
  (let [m (normalize-map decoded)]
    (request-map (:msg-id m) (:request-id m) (:payload m))))

(defn normalize-response
  "Normalize a decoded server->client response or push envelope."
  [decoded]
  (let [m (normalize-map decoded)]
    (response-map (:request-id m) (:payload m))))

(defn dispatch-client-response!
  "Route GUI network payload to push/response handlers by request-id convention.
  request-id < 0 means push payload: {:msg-id ... :payload ...}."
  [request-id payload]
  (let [rid (int (or request-id -1))
        p (normalize-map payload)]
    (if (neg? rid)
      (net-client/handle-push (:msg-id p) (:payload p))
      (net-client/handle-response rid p))))