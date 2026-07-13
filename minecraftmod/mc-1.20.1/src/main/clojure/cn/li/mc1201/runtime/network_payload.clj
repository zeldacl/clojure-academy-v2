(ns cn.li.mc1201.runtime.network-payload
  "Shared helpers for packaging runtime network payloads across loaders."
  (:require [cn.li.mcmod.network.binary-codec :as codec]))

(defn wrap-message
  [msg-id payload]
  {:msg-id msg-id
   :payload payload})

(defn serialize-message
  ^bytes [msg-id payload]
  (codec/encode (wrap-message msg-id payload)))
