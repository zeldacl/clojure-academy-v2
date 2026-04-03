(ns cn.li.ac.ability.context
  "Skill execution Context – state machine and message routing.

  A Context represents a single active skill use session between a player and
  the server. Lifecycle: CONSTRUCTED → ALIVE → TERMINATED (one-way).

  State is held in a plain map and managed via pure functions. The mutable
  atom context-registry (keyed by context-id) tracks live contexts.

  Message routing conventions:
    ctx-send-to-server!  – client sends to server-side handler
    ctx-send-to-client!  – server sends to origin player's client
    ctx-send-to-local!   – local queue, no network hop
    ctx-send-to-except-local! – server multicast to nearby, excluding origin
    ctx-send-to-self!    – both-sided local deliver

  NO net.minecraft.* imports."
  (:require [cn.li.ac.ability.event :as evt]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Status enum (keyword)
;; ============================================================================

(def STATUS-CONSTRUCTED :constructed)
(def STATUS-ALIVE       :alive)
(def STATUS-TERMINATED  :terminated)

(defn status-valid-transition?
  "Only allow forward transitions."
  [from to]
  (case [from to]
    [:constructed :alive]      true
    [:constructed :terminated] true
    [:alive :terminated]       true
    false))

;; ============================================================================
;; Context Map Schema
;; ============================================================================
;;
;; {:id             string    ; client-assigned string ID ("cid-<n>")
;;  :server-id      string | nil   ; assigned by server once alive
;;  :player-uuid    string
;;  :skill-id       keyword
;;  :status         keyword   ; :constructed :alive :terminated
;;  :input-state    keyword   ; :idle :active :released :aborted
;;  :message-buffer []        ; messages queued while CONSTRUCTED
;;  :listeners      {channel-key [fn ...]}
;;  :last-keepalive-ms long | nil}

;; ============================================================================
;; Registry (server-side and client-side contexts)
;; ============================================================================

(defonce ^:private context-registry
  (atom {}))

(defonce ^:private route-fns
  (atom {:to-server nil
         :to-client nil
         :to-except-local nil}))

(defonce ^:private client-id-counter (atom 0))
(defonce ^:private server-id-counter (atom 0))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn new-context
  "Create a new CONSTRUCTED context on the client side."
  [player-uuid skill-id]
  (let [id (str "cid-" (swap! client-id-counter inc))]
    {:id             id
     :server-id      nil
     :player-uuid    player-uuid
     :skill-id       skill-id
     :status         STATUS-CONSTRUCTED
    :input-state    :idle
     :message-buffer []
     :listeners      {}
     :last-keepalive-ms nil}))

(defn new-server-context
  "Create a server-side context linked to a client context by client-id."
  [player-uuid skill-id client-id]
  (let [sid (str "sid-" (swap! server-id-counter inc))]
    {:id             client-id
     :server-id      sid
     :player-uuid    player-uuid
     :skill-id       skill-id
     :status         STATUS-ALIVE      ; server-side starts ALIVE after validation
    :input-state    :idle
     :message-buffer []
     :listeners      {}
     :last-keepalive-ms (System/currentTimeMillis)}))

;; ============================================================================
;; Registry Operations
;; ============================================================================

(defn register-context! [ctx]
  (swap! context-registry assoc (:id ctx) ctx)
  ctx)

(defn get-context [ctx-id]
  (get @context-registry ctx-id))

(defn get-all-contexts
  "Get all registered contexts as {ctx-id -> ctx-data}."
  []
  @context-registry)

(defn update-context! [ctx-id f & args]
  (apply swap! context-registry update ctx-id f args))

(defn remove-context! [ctx-id]
  (swap! context-registry dissoc ctx-id))

(defn get-all-contexts-for-player [player-uuid]
  (filter #(= player-uuid (:player-uuid %)) (vals @context-registry)))

;; ============================================================================
;; Transitions
;; ============================================================================

(defn transition-to-alive!
  "Transition context from CONSTRUCTED → ALIVE. Flush buffered messages.
  Returns context map after transition, or nil if invalid."
  [ctx-id server-id flush-fn]
  (when-let [ctx (get-context ctx-id)]
    (when (status-valid-transition? (:status ctx) STATUS-ALIVE)
      (update-context! ctx-id
                       (fn [c]
                         (let [buffer (:message-buffer c)
                               alive-ctx (assoc c
                                                :status STATUS-ALIVE
                                                :server-id server-id
                                                :message-buffer []
                                                :last-keepalive-ms (System/currentTimeMillis))]
                           ;; Flush buffered messages
                           (when (and flush-fn (seq buffer))
                             (doseq [msg buffer] (flush-fn msg)))
                           alive-ctx)))
      (get-context ctx-id))))

(defn terminate-context!
  "Transition any status → TERMINATED. Fires MSG_TERMINATED."
  [ctx-id send-terminated-fn]
  (when-let [ctx (get-context ctx-id)]
    (when (not= (:status ctx) STATUS-TERMINATED)
      (update-context! ctx-id assoc :status STATUS-TERMINATED)
      (when send-terminated-fn
        (send-terminated-fn ctx-id))
      (log/debug "Context terminated:" ctx-id))))

(defn abort-all-contexts-for-player!
  "Forcibly terminate all contexts for a player (death, category change, etc.)."
  [player-uuid send-terminated-fn]
  (doseq [ctx (get-all-contexts-for-player player-uuid)]
    (terminate-context! (:id ctx) send-terminated-fn)))

;; ============================================================================
;; Keepalive
;; ============================================================================

(def ^:private KEEPALIVE-TIMEOUT-MS 1500)

(defn update-keepalive! [ctx-id]
  (update-context! ctx-id assoc :last-keepalive-ms (System/currentTimeMillis)))

(defn check-keepalive-timeout!
  "Terminate contexts that haven't sent keepalive within timeout."
  [send-terminated-fn]
  (let [now (System/currentTimeMillis)]
    (doseq [[ctx-id ctx] @context-registry]
      (when (and (= (:status ctx) STATUS-ALIVE)
                 (:last-keepalive-ms ctx)
                 (> (- now (:last-keepalive-ms ctx)) KEEPALIVE-TIMEOUT-MS))
        (log/debug "Context keepalive timeout:" ctx-id)
        (terminate-context! ctx-id send-terminated-fn)))))

;; ============================================================================
;; Message Routing
;; ============================================================================

(defn ctx-buffer-or-send!
  "If CONSTRUCTED, buffer the message. If ALIVE, call send-fn immediately."
  [ctx-id msg send-fn]
  (let [ctx (get-context ctx-id)]
    (when ctx
      (if (= (:status ctx) STATUS-CONSTRUCTED)
        (update-context! ctx-id update :message-buffer conj msg)
        (when send-fn (send-fn msg))))))

(defn ctx-send-to-local!
  "Deliver message to all local listeners for the given channel."
  [ctx-id channel msg]
  (when-let [ctx (get-context ctx-id)]
    (doseq [h (get-in ctx [:listeners channel] [])]
      (try (h msg) (catch Exception e (log/warn "Listener threw" (ex-message e)))))))

(defn register-route-fns!
  "Register platform route callbacks used by context message APIs.

  Expected callback signatures:
  - to-server       (fn [ctx-id channel msg])
  - to-client       (fn [ctx-id channel msg])
  - to-except-local (fn [ctx-id channel msg])"
  [{:keys [to-server to-client to-except-local]}]
  (reset! route-fns {:to-server to-server
                     :to-client to-client
                     :to-except-local to-except-local}))

(defn ctx-send-to-server!
  "Route context message to server side.
  If context is CONSTRUCTED, message is buffered and sent after ESTABLISH."
  [ctx-id channel msg]
  (ctx-buffer-or-send! ctx-id
                       {:channel channel :payload msg}
                       (fn [m]
                         (when-let [f (:to-server @route-fns)]
                           (f ctx-id (:channel m) (:payload m))))))

(defn ctx-send-to-client!
  "Route context message to origin client side.
  If context is CONSTRUCTED, message is buffered and sent after ESTABLISH."
  [ctx-id channel msg]
  (ctx-buffer-or-send! ctx-id
                       {:channel channel :payload msg}
                       (fn [m]
                         (when-let [f (:to-client @route-fns)]
                           (f ctx-id (:channel m) (:payload m))))))

(defn ctx-send-to-except-local!
  "Broadcast context message to nearby receivers except local player.
  If context is CONSTRUCTED, message is buffered and sent after ESTABLISH."
  [ctx-id channel msg]
  (ctx-buffer-or-send! ctx-id
                       {:channel channel :payload msg}
                       (fn [m]
                         (when-let [f (:to-except-local @route-fns)]
                           (f ctx-id (:channel m) (:payload m))))))

(defn ctx-send-to-self!
  "Deliver context message to local listeners immediately."
  [ctx-id channel msg]
  (ctx-send-to-local! ctx-id channel msg))

(defn ctx-on!
  "Register a listener for channel on a context."
  [ctx-id channel handler-fn]
  (update-context! ctx-id update-in [:listeners channel] (fnil conj []) handler-fn))
