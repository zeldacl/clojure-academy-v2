(ns cn.li.mcmod.network.server
  "Server-side RPC handler registry for GUI/network logic"
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private handlers (atom {}))
(defonce ^:private handlers-frozen? (atom false))

(defn- assert-not-frozen!
  []
  (when @handlers-frozen?
    (throw (ex-info "Network server handlers are frozen" {}))))

(defn register-handler
  "Register a request handler.

  Args:
  - msg-id: string message identifier
  - handler-fn: (fn [payload player] response-map)"
  [msg-id handler-fn]
  (assert-not-frozen!)
  (swap! handlers
         (fn [registry]
           (if-let [existing (get registry msg-id)]
             (if (identical? existing handler-fn)
               registry
               (throw (ex-info "Conflicting network handler id" {:msg-id msg-id})))
             (assoc registry msg-id handler-fn))))
  (log/info "Registered network handler for" msg-id)
  nil)

(defn freeze-handlers!
  []
  (reset! handlers-frozen? true)
  nil)

(defn reset-handlers-for-test!
  []
  (reset! handlers {})
  (reset! handlers-frozen? false)
  nil)

(defn handlers-snapshot
  []
  {:handlers @handlers
   :frozen? @handlers-frozen?})

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
