(ns cn.li.mc1201.gui.network-packet-base
  "Shared packet model helpers for GUI/network bridges.

  This namespace is intentionally data-only and loader-agnostic."
  (:require [clojure.string :as str]
            [cn.li.mc1201.runtime.edn-state :as es]))

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
