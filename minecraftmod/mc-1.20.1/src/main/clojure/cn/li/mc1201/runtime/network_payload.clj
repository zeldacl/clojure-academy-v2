(ns cn.li.mc1201.runtime.network-payload
  "Shared helpers for packaging runtime network payloads across loaders.")

(defn wrap-message
  [msg-id payload]
  {:msg-id msg-id
   :payload payload})

(defn serialize-message
  ^bytes [msg-id payload]
  (.getBytes (pr-str (wrap-message msg-id payload)) "UTF-8"))
