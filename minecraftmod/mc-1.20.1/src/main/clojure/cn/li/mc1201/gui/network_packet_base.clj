(ns cn.li.mc1201.gui.network-packet-base
  "Shared packet model helpers for GUI/network bridges.

  This namespace is intentionally data-only and loader-agnostic."
  (:require [clojure.string :as str]))

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
