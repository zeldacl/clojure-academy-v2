(ns cn.li.mcmod.network.client
  "Client-side RPC request/response support for GUI/network logic"
  (:require [cn.li.mcmod.gui.owner-contract :as owner-contract]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(def ^:private CLIENT-RPC-CHANNEL :client-rpc)
(def ^:private DEFAULT-REQUEST-TIMEOUT-MS 30000)

(defn- initial-client-network-session-state
  []
  {:request-counter {}
   :pending-requests {}
   :push-handlers {}})

(defn- initial-client-runtime-state
  []
  {:global-push-handlers {}
   :installed-sessions {}})

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Client network owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn- client-session-id
  [owner]
  (if (some? owner)
    (:client-session-id (owner-contract/require-client-owner owner))
    (require-owner-value owner ":client-session-id"
                         (runtime-hooks/client-session-id))))

(defn- maybe-client-session-id
  [owner]
  (or (some-> owner :client-session-id)
      (runtime-hooks/client-session-id)))

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
  (client-owner-key owner))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- request-key [owner-key request-id]
  [owner-key request-id])

(defn create-client-network-session
  ([]
   (create-client-network-session {}))
  ([{:keys [now-ms-fn]
     :or {now-ms-fn now-ms}}]
   (let [state* (atom (initial-client-network-session-state))]
     (letfn [(snapshot []
               @state*)
             (dispose! []
               (reset! state* (initial-client-network-session-state))
               nil)
             (next-request-id! [owner-key]
               (get-in (swap! state* update-in [:request-counter owner-key] (fnil inc 0))
                       [:request-counter owner-key]))
             (register-callback! [owner-key request-id callback timeout-ms]
               (when callback
                 (swap! state* assoc-in [:pending-requests (request-key owner-key request-id)]
                        {:owner-key owner-key
                         :request-id request-id
                         :callback callback
                         :created-at-ms (now-ms-fn)
                         :timeout-ms (long (or timeout-ms DEFAULT-REQUEST-TIMEOUT-MS))}))
               nil)
             (find-pending-request-entry [owner-key request-id]
               (let [key (request-key owner-key request-id)]
                 (when-let [entry (get-in @state* [:pending-requests key])]
                   [key entry])))
             (clear-owner! [owner-key]
               (swap! state*
                      (fn [{:keys [pending-requests push-handlers] :as state}]
                        (-> state
                            (assoc :pending-requests
                                   (into {}
                                         (remove (fn [[[entry-owner _request-id] _entry]]
                                                   (= owner-key entry-owner))
                                                 pending-requests)))
                            (assoc :push-handlers
                                   (into {}
                                         (remove (fn [[[handler-owner _msg-id] _handler]]
                                                   (= owner-key handler-owner))
                                                 push-handlers)))
                            (update :request-counter dissoc owner-key))))
               nil)
             (expire-pending! [current-ms]
               (let [expired (atom [])]
                 (swap! state* update :pending-requests
                        (fn [entries]
                          (into {}
                                (remove (fn [[key {:keys [created-at-ms timeout-ms]}]]
                                          (let [expired? (>= (- (long current-ms) (long created-at-ms))
                                                             (long timeout-ms))]
                                            (when expired?
                                              (swap! expired conj key))
                                            expired?))
                                        entries))))
                 @expired))
             (handle-response! [owner-key request-id response]
               (if-let [[pending-key {:keys [callback]}] (find-pending-request-entry owner-key request-id)]
                 (do
                   (swap! state* update :pending-requests dissoc pending-key)
                   (try
                     (callback response)
                     (catch Exception e
                       (log/error "Error in response callback (request-id=" request-id "):" (ex-message e))
                       (log/stacktrace "Error in response callback" e))))
                 (log/warn "No pending request for response" request-id))
               nil)
             (register-owner-push-handler! [owner-key msg-id handler-fn]
               (swap! state* assoc-in [:push-handlers [owner-key msg-id]] handler-fn)
               nil)
             (unregister-owner-push-handler! [owner-key msg-id]
               (swap! state* update :push-handlers dissoc [owner-key msg-id])
               nil)
             (has-owner-push-handler-msg-id? [msg-id]
               (boolean
                (some (fn [[[ _owner-key entry-msg-id] _handler-fn]]
                        (= msg-id entry-msg-id))
                      (:push-handlers @state*))))
             (handle-owner-push! [owner-key msg-id payload]
               (if-let [handler (get-in @state* [:push-handlers [owner-key msg-id]])]
                 (try
                   (handler payload)
                   true
                   (catch Exception e
                     (log/error "Error in push handler" msg-id ":" (ex-message e))
                     (log/stacktrace "Error in push handler" e)
                     true))
                 false))]
       {:kind ::client-network-session
        :snapshot snapshot
        :dispose! dispose!
        :next-request-id! next-request-id!
        :register-callback! register-callback!
        :clear-owner! clear-owner!
        :expire-pending! expire-pending!
        :handle-response! handle-response!
        :register-owner-push-handler! register-owner-push-handler!
        :unregister-owner-push-handler! unregister-owner-push-handler!
        :has-owner-push-handler-msg-id? has-owner-push-handler-msg-id?
        :handle-owner-push! handle-owner-push!}))))

(defn client-network-session?
  [value]
  (= ::client-network-session (:kind value)))

(defn create-client-runtime
  ([]
   (create-client-runtime {}))
  ([{:keys [create-session-fn]
     :or {create-session-fn create-client-network-session}}]
   (let [state* (atom (initial-client-runtime-state))]
     (letfn [(snapshot []
               @state*)
             (global-push-handler [msg-id]
               (get-in @state* [:global-push-handlers msg-id]))
             (register-global-push-handler! [msg-id handler-fn]
               (swap! state* assoc-in [:global-push-handlers msg-id] handler-fn)
               nil)
             (unregister-global-push-handler! [msg-id]
               (swap! state* update :global-push-handlers dissoc msg-id)
               nil)
             (installed-client-network-session [session-id]
               (get-in @state* [:installed-sessions session-id]))
             (ensure-installed-client-network-session! [session-id]
               (let [created (create-session-fn)]
                 (get-in (swap! state*
                                (fn [runtime]
                                  (if (get-in runtime [:installed-sessions session-id])
                                    runtime
                                    (assoc-in runtime [:installed-sessions session-id] created))))
                         [:installed-sessions session-id])))
             (clear-client-session-state! [session-id]
               (when-let [session (installed-client-network-session session-id)]
                 ((:dispose! session)))
               (swap! state* update :installed-sessions dissoc session-id)
               nil)
             (dispose! []
               (doseq [session (vals (:installed-sessions @state*))]
                 ((:dispose! session)))
               (reset! state* (initial-client-runtime-state))
               nil)]
       {:kind ::client-runtime
        :state* state*
        :snapshot snapshot
        :dispose! dispose!
        :global-push-handler global-push-handler
        :register-global-push-handler! register-global-push-handler!
        :unregister-global-push-handler! unregister-global-push-handler!
        :installed-client-network-session installed-client-network-session
        :ensure-installed-client-network-session! ensure-installed-client-network-session!
        :clear-client-session-state! clear-client-session-state!}))))

;; Client runtime stored in Framework [:service :network-client]

(def ^:private client-path [:service :network-client])

(defn- ensure-client-runtime
  "Lazy-init the client runtime in Framework on first access."
  []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom client-path)
        (let [rt (create-client-runtime)]
          (swap! fw-atom assoc-in client-path rt)
          rt))
    (create-client-runtime)))

(defn client-runtime?
  [value]
  (= ::client-runtime (:kind value)))

(defn current-client-runtime
  []
  (ensure-client-runtime))

(defn client-runtime-state-atom
  []
  (:state* (current-client-runtime)))

(defn client-runtime-state-snapshot
  []
  ((:snapshot (current-client-runtime))))

(declare unregister-push-handler!)

(defn- owner-client-network-session
  [owner]
  (when-let [session (:client-network-session (or owner {}))]
    (if (client-network-session? session)
      session
      (throw (ex-info "Client network owner provided invalid :client-network-session"
                      {:owner owner
                       :value session})))))

(defn- installed-client-network-session
  [session-id]
  ((:installed-client-network-session (current-client-runtime)) session-id))

(defn- ensure-installed-client-network-session!
  [session-id]
  ((:ensure-installed-client-network-session! (current-client-runtime)) session-id))

(defn- resolve-client-network-session
  ([owner]
   (resolve-client-network-session owner {}))
  ([owner {:keys [allow-install?]
           :or {allow-install? false}}]
   (or (owner-client-network-session owner)
       (when-let [session-id (maybe-client-session-id owner)]
         (if allow-install?
           (ensure-installed-client-network-session! session-id)
           (installed-client-network-session session-id))))))

(defn- runtime-global-push-handler
  [msg-id]
  ((:global-push-handler (current-client-runtime)) msg-id))

(defn clear-owner-state!
  [owner]
  (let [owner-key (client-owner-key owner)]
    (when-let [session (resolve-client-network-session owner {:allow-install? false})]
      ((:clear-owner! session) owner-key))
    nil))

(defn clear-client-session-state!
  [client-session-id]
  ((:clear-client-session-state! (current-client-runtime)) client-session-id))

(defn expire-pending-requests!
  ([]
   (expire-pending-requests! (now-ms)))
  ([current-ms]
   (vec (mapcat #((:expire-pending! %) current-ms)
                (vals (:installed-sessions (client-runtime-state-snapshot))))))
  ([session-or-owner current-ms]
   (let [session (if (client-network-session? session-or-owner)
                   session-or-owner
                   (resolve-client-network-session session-or-owner {:allow-install? false}))]
     (if session
       (vec ((:expire-pending! session) current-ms))
       []))))

(defn reset-client-state-for-test!
  []
  ((:dispose! (current-client-runtime)))
  nil)

(defn client-state-snapshot
  ([]
   (client-state-snapshot nil))
  ([session-or-owner]
   (let [session (if (client-network-session? session-or-owner)
                   session-or-owner
                   (resolve-client-network-session session-or-owner {:allow-install? false}))
         session-snapshot (if session
                            ((:snapshot session))
                            (initial-client-network-session-state))
         {:keys [global-push-handlers installed-sessions]} (client-runtime-state-snapshot)]
     (assoc session-snapshot
            :global-push-handlers global-push-handlers
            :installed-sessions (vec (keys installed-sessions))))))

(defn handle-response
  "Handle a response from server.

  Args:
  - request-id: int
  - response: map returned by server handler"
  ([request-id response]
   (handle-response nil request-id response))
  ([owner request-id response]
   (let [owner-key (client-owner-key owner)]
     (if-let [session (resolve-client-network-session owner {:allow-install? false})]
       ((:handle-response! session) owner-key request-id response)
       (log/warn "No pending request for response" request-id)))))

(defn register-push-handler!
  "Register one-way client push handler.

  Args:
  - msg-id: string message identifier
  - handler-fn: (fn [payload])"
  ([msg-id handler-fn]
   ((:register-global-push-handler! (current-client-runtime)) msg-id handler-fn)
   (fn []
     (unregister-push-handler! msg-id)))
  ([owner msg-id handler-fn]
   (let [session (resolve-client-network-session owner {:allow-install? true})
         owner-key (push-owner-key owner)]
     ((:register-owner-push-handler! session) owner-key msg-id handler-fn)
     (fn []
       (unregister-push-handler! owner msg-id)))))

(defn unregister-push-handler!
  ([msg-id]
   ((:unregister-global-push-handler! (current-client-runtime)) msg-id))
  ([owner msg-id]
   (when-let [session (resolve-client-network-session owner {:allow-install? false})]
     ((:unregister-owner-push-handler! session) (push-owner-key owner) msg-id))
   nil))

(defn- owner-requires-scoped-push-handler?
  [owner]
  (or (:screen-id owner)
      (:channel-id owner)))

(defn handle-push
  "Handle one-way server push message.

  Args:
  - msg-id: string message identifier
  - payload: map"
  ([msg-id payload]
   (if-let [handler (runtime-global-push-handler msg-id)]
     (try
       (handler payload)
       (catch Exception e
         (log/error "Error in push handler" msg-id ":" (ex-message e))
         (log/stacktrace "Error in push handler" e)))
     (log/warn "No push handler registered for" msg-id)))
  ([owner msg-id payload]
   (if-let [session (resolve-client-network-session owner {:allow-install? false})]
     (if ((:handle-owner-push! session) (push-owner-key owner) msg-id payload)
       nil
       (if (or (owner-requires-scoped-push-handler? owner)
               ((:has-owner-push-handler-msg-id? session) msg-id))
         (log/warn "No push handler registered for" msg-id)
         (handle-push msg-id payload)))
     (if (owner-requires-scoped-push-handler? owner)
       (log/warn "No push handler registered for" msg-id)
       (handle-push msg-id payload)))))

(defmulti send-request
  "Platform-specific transport for RPC requests"
  (fn [_msg-id _payload _request-id] (platform-dispatch/current-platform-version)))

(defmethod send-request :default [_msg-id _payload _request-id]
  (throw (ex-info "No network transport for version"
                  {:version (platform-dispatch/current-platform-version)})))

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
   (let [owner* (when owner (owner-contract/require-client-owner owner))
         session (resolve-client-network-session owner* {:allow-install? true})
         owner-key (client-owner-key owner*)
         request-id (if callback
                      ((:next-request-id! session) owner-key)
                      -1)]
     ((:register-callback! session) owner-key request-id callback (when (map? owner*) (:timeout-ms owner*)))
     (send-request msg-id payload request-id))))
