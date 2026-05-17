(ns cn.li.ac.wireless.gui.events.handlers
  "Event handlers for wireless GUI events.
  
  This namespace contains the business logic for handling user actions:
  - Network connect/disconnect
  - List refresh
  - Error handling
  
  Key design: Handlers are pure functions that can be tested independently."
  (:require [cn.li.ac.wireless.gui.protocol :as proto]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Core Event Handlers
;; ============================================================================

(defn create-connect-handler
  "Create handler for connect event.
  
  Args:
    state-container: IStateContainer
    tile-position: {:x :y :z}
    connect-msg-fn: () -> server message
    connect-payload-fn: (tile-pos target-network password) -> payload
    on-success: (response) -> void
    on-error: (error) -> void
    
  Returns:
    Handler function: (event-data) -> void"
  [state-container tile-position connect-msg-fn connect-payload-fn on-success on-error]
  (fn [event-data]
    (try
      (let [{:keys [network password]} event-data
            payload (connect-payload-fn tile-position network password)]
        (net-client/send-to-server
          (connect-msg-fn)
          payload
          (fn [resp]
            (proto/set-state! state-container :connected-network (:ssid network))
            (proto/set-state! state-container :is-connected? true)
            (when on-success
              (on-success resp)))))
      (catch Exception ex
        (log/error (str "Connect handler error: " ex))
        (when on-error
          (on-error ex))))))

(defn create-disconnect-handler
  "Create handler for disconnect event.
  
  Args:
    state-container: IStateContainer
    tile-position: {:x :y :z}
    disconnect-msg-fn: () -> server message
    on-success: (response) -> void
    on-error: (error) -> void
    
  Returns:
    Handler function: (event-data) -> void"
  [state-container tile-position disconnect-msg-fn on-success on-error]
  (fn [_event-data]
    (try
      (net-client/send-to-server
        (disconnect-msg-fn)
        tile-position
        (fn [resp]
          (proto/set-state! state-container :connected-network nil)
          (proto/set-state! state-container :is-connected? false)
          (when on-success
            (on-success resp))))
      (catch Exception ex
        (log/error (str "Disconnect handler error: " ex))
        (when on-error
          (on-error ex))))))

(defn create-refresh-handler
  "Create handler for refresh/list-update event.
  
  Args:
    state-container: IStateContainer
    tile-position: {:x :y :z}
    list-msg-fn: () -> server message
    on-networks: (networks) -> void
    on-error: (error) -> void
    
  Returns:
    Handler function: (event-data) -> void"
  [state-container tile-position list-msg-fn on-networks on-error]
  (fn [_event-data]
    (try
      (net-client/send-to-server
        (list-msg-fn)
        tile-position
        (fn [resp]
          (proto/set-state! state-container :available-networks (vec (:avail resp [])))
          (proto/set-state! state-container :last-refresh (System/currentTimeMillis))
          (when on-networks
            (on-networks (:avail resp [])))))
      (catch Exception ex
        (log/error (str "Refresh handler error: " ex))
        (when on-error
          (on-error ex))))))

;; ============================================================================
;; Error Handler
;; ============================================================================

(defn create-error-handler
  "Create handler for error events.
  
  Args:
    state-container: IStateContainer
    on-error: (message error) -> void
    
  Returns:
    Handler function: (event-data) -> void"
  [state-container on-error]
  (fn [event-data]
    (let [{:keys [message error]} event-data]
      (log/error (str "Wireless GUI error: " message))
      (proto/set-state! state-container :last-error message)
      (proto/set-state! state-container :error-time (System/currentTimeMillis))
      (when on-error
        (on-error message error)))))

;; ============================================================================
;; Handler Registration
;; ============================================================================

(defn register-handlers!
  "Register all event handlers on event bus.
  
  Args:
    event-bus: IEventBus
    state-container: IStateContainer
    tile-position: {:x :y :z}
    config: {:connect-msg-fn ... :disconnect-msg-fn ... :list-msg-fn ...
             :connect-payload-fn ... :callbacks {...}}
    
  Returns:
    unsubscribe-fns: [fn1 fn2 ...]"
  [event-bus state-container tile-position config]
  (let [{:keys [connect-msg-fn disconnect-msg-fn list-msg-fn 
                connect-payload-fn callbacks]} config
        {:keys [on-connect-success on-disconnect-success on-networks-loaded 
                on-error]} callbacks]
    
    [(proto/subscribe event-bus :connect
       (create-connect-handler state-container tile-position 
                              connect-msg-fn connect-payload-fn
                              on-connect-success on-error))
     
     (proto/subscribe event-bus :disconnect
       (create-disconnect-handler state-container tile-position
                                 disconnect-msg-fn on-disconnect-success on-error))
     
     (proto/subscribe event-bus :list-update
       (create-refresh-handler state-container tile-position
                              list-msg-fn on-networks-loaded on-error))
     
     (proto/subscribe event-bus :refresh
       (create-refresh-handler state-container tile-position
                              list-msg-fn on-networks-loaded on-error))
     
     (proto/subscribe event-bus :error
       (create-error-handler state-container on-error))]))

(defn unregister-all-handlers!
  "Unregister all handlers.
  
  Args:
    unsub-fns: [fn1 fn2 ...] from register-handlers!
    
  Returns:
    nil"
  [unsub-fns]
  (doseq [unsub unsub-fns]
    (unsub)))

;; ============================================================================
;; Async Handler Wrappers
;; ============================================================================

(defn with-loading-state
  "Wrap handler with loading state management.
  
  Args:
    state-container: IStateContainer
    handler: Original handler function
    
  Returns:
    Wrapped handler that manages :loading state"
  [state-container handler]
  (fn [event-data]
    (proto/set-state! state-container :loading true)
    (try
      (handler event-data)
      (finally
        (proto/set-state! state-container :loading false)))))

(defn with-error-recovery
  "Wrap handler with error recovery.
  
  Args:
    state-container: IStateContainer
    handler: Original handler function
    
  Returns:
    Wrapped handler with error catching"
  [state-container handler]
  (fn [event-data]
    (try
      (handler event-data)
      (catch Exception ex
        (log/error (str "Handler error: " ex))
        (proto/set-state! state-container :last-error (.getMessage ex))))))

;; ============================================================================
;; Testing Helpers
;; ============================================================================

(defn create-mock-handlers
  "Create mock handlers for testing.
  
  Returns:
    {:on-connect-success fn
     :on-disconnect-success fn
     :on-networks-loaded fn
     :on-error fn
     :events atom of all calls}"
  []
  (let [events (atom [])]
    {:on-connect-success (fn [resp] (swap! events conj [:connect-success resp]))
     :on-disconnect-success (fn [resp] (swap! events conj [:disconnect-success resp]))
     :on-networks-loaded (fn [nets] (swap! events conj [:networks-loaded nets]))
     :on-error (fn [msg err] (swap! events conj [:error msg err]))
     :events events}))
