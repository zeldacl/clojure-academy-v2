(ns cn.li.fabric1201.gui.network
  "Fabric 1.20.1 GUI/RPC network facade.

  Server-safe entrypoint that keeps client-only networking in a dedicated
  namespace loaded lazily from client init." 
  (:require [cn.li.fabric1201.gui.network.server :as server]))

(def send-push-to-client!
  server/send-push-to-client!)

(def init-server!
  server/init-server!)

(defn init-client!
  []
  ((requiring-resolve 'cn.li.fabric1201.gui.network.client/init-client!)))

(defn send-to-server!
  [msg-id request-id payload]
  ((requiring-resolve 'cn.li.fabric1201.gui.network.client/send-to-server!)
   msg-id
   request-id
   payload))
