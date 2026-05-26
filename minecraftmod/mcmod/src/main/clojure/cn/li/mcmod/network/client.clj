(ns cn.li.mcmod.network.client
  "Client-side RPC request/response support for GUI/network logic"
  (:require [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private request-counter (atom 0))
(defonce ^:private pending-requests (atom {}))
(defonce ^:private push-handlers (atom {}))

(def ^:private CLIENT-RPC-CHANNEL :client-rpc)
(def ^:private GLOBAL-PUSH-OWNER-KEY [:static-push-handlers])
(def ^:private DEFAULT-REQUEST-TIMEOUT-MS 30000)

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Client network owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn- client-session-id
  [owner]
  (require-owner-value owner ":client-session-id"
                       (or (:client-session-id owner)
                           (:session-id owner)
                           runtime-hooks/*client-session-id*)))

(defn- client-channel-id
  [owner]
  (or (:screen-id owner)
      (:channel-id owner)
      CLIENT-RPC-CHANNEL))

(defn client-owner-key
  [owner]
  [(client-session-id owner)
   (client-channel-id owner)])

(defn- push-owner-key
  [owner]
  (if (some? owner)
    (client-owner-key owner)
    GLOBAL-PUSH-OWNER-KEY))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- next-request-id [owner-key]
  (get (swap! request-counter update owner-key (fnil inc 0)) owner-key))

(defn- request-key [owner-key request-id]
  [owner-key request-id])

(defn- register-callback! [owner-key request-id callback timeout-ms]
  (when callback
    (swap! pending-requests assoc (request-key owner-key request-id)
           {:owner-key owner-key
            :request-id request-id
            :callback callback
            :created-at-ms (now-ms)
            :timeout-ms (long (or timeout-ms DEFAULT-REQUEST-TIMEOUT-MS))})))

(defn- find-pending-request-entry [owner-key request-id]
  (let [key (request-key owner-key request-id)]
    (when-let [entry (get @pending-requests key)]
      [key entry])))

(defn clear-owner-state!
  [owner]
  (let [owner-key (client-owner-key owner)]
    (swap! pending-requests
           (fn [entries]
             (into {}
                   (remove (fn [[[entry-owner _request-id] _entry]]
                             (= owner-key entry-owner)))
                   entries)))
    (swap! push-handlers
           (fn [handlers]
             (into {}
                   (remove (fn [[[handler-owner _msg-id] _handler]]
                             (= owner-key handler-owner)))
                   handlers)))
    (swap! request-counter dissoc owner-key)
    nil))

(defn expire-pending-requests!
  ([]
   (expire-pending-requests! (now-ms)))
  ([current-ms]
   (let [expired (atom [])]
     (swap! pending-requests
            (fn [entries]
              (into {}
                    (remove (fn [[key {:keys [created-at-ms timeout-ms]}]]
                              (let [expired? (>= (- (long current-ms) (long created-at-ms))
                                                 (long timeout-ms))]
                                (when expired? (swap! expired conj key))
                                expired?)))
                    entries)))
     @expired)))

(defn reset-client-state-for-test!
  []
  (reset! request-counter {})
  (reset! pending-requests {})
  (reset! push-handlers {})
  nil)

(defn client-state-snapshot
  []
  {:request-counter @request-counter
   :pending-requests @pending-requests
   :push-handlers @push-handlers})

(defn handle-response
  "Handle a response from server.

  Args:
  - request-id: int
  - response: map returned by server handler"
  ([request-id response]
   (handle-response nil request-id response))
  ([owner request-id response]
  (if-let [[request-key {:keys [callback]}] (find-pending-request-entry (client-owner-key owner) request-id)]
    (do
      (swap! pending-requests dissoc request-key)
      (try
        (callback response)
        (catch Exception e
          (log/error "Error in response callback:"(ex-message e)))))
    (log/warn "No pending request for response" request-id))))

(defn register-push-handler!
  "Register one-way client push handler.

  Args:
  - msg-id: string message identifier
  - handler-fn: (fn [payload])"
  ([msg-id handler-fn]
    (swap! push-handlers assoc [GLOBAL-PUSH-OWNER-KEY msg-id] handler-fn)
    nil)
  ([owner msg-id handler-fn]
    (swap! push-handlers assoc [(push-owner-key owner) msg-id] handler-fn)
   nil))

(defn unregister-push-handler!
  ([msg-id]
    (swap! push-handlers dissoc [GLOBAL-PUSH-OWNER-KEY msg-id])
    nil)
  ([owner msg-id]
    (swap! push-handlers dissoc [(push-owner-key owner) msg-id])
   nil))

(defn handle-push
  "Handle one-way server push message.

  Args:
  - msg-id: string message identifier
  - payload: map"
  ([msg-id payload]
   (if-let [handler (get @push-handlers [GLOBAL-PUSH-OWNER-KEY msg-id])]
     (try
       (handler payload)
       (catch Exception e
         (log/error "Error in push handler" msg-id ":" (ex-message e))))
     (log/warn "No push handler registered for" msg-id)))
  ([owner msg-id payload]
  (if-let [handler (get @push-handlers [(push-owner-key owner) msg-id])]
    (try
      (handler payload)
      (catch Exception e
        (log/error "Error in push handler" msg-id ":" (ex-message e))))
    (log/warn "No push handler registered for" msg-id))))

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
    (send-to-server nil msg-id payload callback))
    ([owner msg-id payload callback]
    (let [owner-key (client-owner-key owner)
        request-id (if callback (next-request-id owner-key) -1)]
      (register-callback! owner-key request-id callback (when (map? owner) (:timeout-ms owner)))
      (send-request msg-id payload request-id))))
