(ns cn.li.mcmod.network.server
  "Server-side RPC handler registry for GUI/network logic"
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private handlers (atom {}))

(defn register-handler
  "Register a request handler.

  Args:
  - msg-id: string message identifier
  - handler-fn: (fn [payload player] response-map)"
  [msg-id handler-fn]
  (swap! handlers assoc msg-id handler-fn)
  (log/info "Registered network handler for" msg-id))

(defn handle-request
  "Handle an incoming request and send a response if needed.

  Args:
  - msg-id: string message identifier
  - request-id: int
  - payload: map
  - player: player entity
  - respond-fn: (fn [request-id response-map]) or nil"
  [msg-id request-id payload player respond-fn]
  (if-let [handler (get @handlers msg-id)]
    (try
      (let [response (handler payload player)]
        (when (and respond-fn (>= request-id 0))
          (respond-fn request-id (or response {}))))
      (catch Exception e
        (log/error "Error handling request" msg-id ":"(ex-message e))
        (when (and respond-fn (>= request-id 0))
          (respond-fn request-id {:success false :error(ex-message e)}))))
    (do
      (log/warn "No handler registered for" msg-id)
      (when (and respond-fn (>= request-id 0))
        (respond-fn request-id {:success false :error "no-handler"})))))
