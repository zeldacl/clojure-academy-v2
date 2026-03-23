(ns cn.li.mcmod.network.client
  "Client-side RPC request/response support for GUI/network logic"
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private request-counter (atom 0))
(defonce ^:private pending-requests (atom {}))

(defn- next-request-id []
  (swap! request-counter inc))

(defn- register-callback! [request-id callback]
  (when callback
    (swap! pending-requests assoc request-id callback)))

(defn handle-response
  "Handle a response from server.

  Args:
  - request-id: int
  - response: map returned by server handler"
  [request-id response]
  (if-let [callback (get @pending-requests request-id)]
    (do
      (swap! pending-requests dissoc request-id)
      (try
        (callback response)
        (catch Exception e
          (log/error "Error in response callback:" ((ex-message e))))))
    (log/warn "No pending request for response" request-id)))

(defmulti send-request
  "Platform-specific transport for RPC requests"
  (fn [_msg-id _payload _request-id] platform-dispatch/*platform-version*))

(defmethod send-request :default [_msg-id _payload _request-id]
  (throw (ex-info "No network transport for version"
                  {:version platform-dispatch/*platform-version*})))

(defn send-to-server
  "Send a request to server and optionally receive a response.

  Args:
  - msg-id: string message identifier
  - payload: map of data
  - callback: (fn [response]) or nil"
  ([msg-id payload]
   (send-to-server msg-id payload nil))
  ([msg-id payload callback]
   (let [request-id (if callback (next-request-id) -1)]
     (register-callback! request-id callback)
     (send-request msg-id payload request-id))))
