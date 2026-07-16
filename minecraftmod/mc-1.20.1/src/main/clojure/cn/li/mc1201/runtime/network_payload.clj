(ns cn.li.mc1201.runtime.network-payload
  "Shared helpers for packaging runtime network payloads across loaders."
  (:require [cn.li.mc1201.runtime.sync-codec :as sync-codec]
            [cn.li.mcmod.network.binary-codec :as codec]))

(def ^:const runtime-sync-message-id "ability:sync/runtime-v2")

(defn wrap-message
  [msg-id payload]
  {:msg-id msg-id
   :payload payload})

(defn serialize-message
  ^bytes [msg-id payload]
  (if (= runtime-sync-message-id msg-id)
    (sync-codec/encode-bytes payload)
    (codec/encode (wrap-message msg-id payload))))
