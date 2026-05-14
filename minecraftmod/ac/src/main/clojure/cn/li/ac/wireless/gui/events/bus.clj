(ns cn.li.ac.wireless.gui.events.bus
  "Event bus for wireless GUI system.
  
  Key design principle: **Pure event bus**, independent from GUI.
  
  - Can be tested without any UI framework
  - No GUI component references
  - Thread-safe publish/subscribe
  - Event type filtering"
  (:require [cn.li.ac.wireless.gui.protocol :as proto]
            [cn.li.ac.foundation.concurrency :as conc]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Event Bus Implementation
;; ============================================================================

(defn create-event-bus
  "Create a new event bus.
  
  Returns:
    Reified IEventBus implementation"
  []
  (let [subscribers-atom (atom {})]  ;; {event-type: [{:id id :handler fn}]}
    
    (reify proto/IEventBus
      
      (publish [this event-type event-data]
        "Publish event to all registered handlers.
        
        Args:
          event-type: Keyword identifying event
          event-data: Event details
          
        Returns:
          true if any handler was called"
        (let [handlers (get @subscribers-atom event-type [])
              handler-count (count handlers)]
          (doseq [{:keys [handler]} handlers]
            (try
              (handler event-data)
              (catch Exception ex
                (log/error (str "Error in event handler for " event-type ": " ex)))))
          (> handler-count 0)))
      
      (subscribe [this event-type handler]
        "Register handler for event type.
        
        Returns:
          unsubscribe function"
        (let [handler-id (gensym (str event-type "-handler-"))]
          (swap! subscribers-atom 
                 update event-type 
                 (fn [handlers] 
                   (conj (or handlers []) {:id handler-id :handler handler})))
          ;; Return unsubscribe function
          (fn []
            (swap! subscribers-atom 
                   update event-type 
                   (fn [handlers]
                     (filterv #(not= (:id %) handler-id) handlers))))))
      
      (unsubscribe-all [this event-type]
        "Remove all handlers for event type.
        
        Returns:
          count of removed handlers"
        (let [handlers (get @subscribers-atom event-type [])
              count (count handlers)]
          (swap! subscribers-atom dissoc event-type)
          count))
      
      (get-handlers-count [this event-type]
        "Get number of handlers for event type.
        
        Returns:
          int: Number of handlers"
        (count (get @subscribers-atom event-type []))))))

;; ============================================================================
;; Convenience Wrappers
;; ============================================================================

(defn on-event
  "Subscribe to single event (convenience wrapper).
  
  Args:
    event-bus: IEventBus
    event-type: Keyword
    handler: (event-data) -> void
    
  Returns:
    unsubscribe-fn"
  [event-bus event-type handler]
  (proto/subscribe event-bus event-type handler))

(defn fire-event
  "Publish event (convenience wrapper).
  
  Args:
    event-bus: IEventBus
    event-type: Keyword
    event-data: Map
    
  Returns:
    boolean: true if any handler was called"
  [event-bus event-type event-data]
  (proto/publish event-bus event-type event-data))

(defn listen-once
  "Subscribe to event, auto-unsubscribe after first call.
  
  Args:
    event-bus: IEventBus
    event-type: Keyword
    handler: (event-data) -> void
    
  Returns:
    unsubscribe-fn"
  [event-bus event-type handler]
  (let [unsub-atom (atom nil)]
    (reset! unsub-atom 
            (proto/subscribe 
              event-bus event-type
              (fn [event-data]
                (@unsub-atom)
                (handler event-data))))
    @unsub-atom))

;; ============================================================================
;; Filtering and Transformation
;; ============================================================================

(defn filter-events
  "Subscribe to events matching a predicate.
  
  Args:
    event-bus: IEventBus
    event-type: Keyword
    predicate: (event-data) -> boolean
    handler: (event-data) -> void
    
  Returns:
    unsubscribe-fn"
  [event-bus event-type predicate handler]
  (proto/subscribe 
    event-bus event-type
    (fn [event-data]
      (when (predicate event-data)
        (handler event-data)))))

(defn map-events
  "Subscribe to events with transformation.
  
  Args:
    event-bus: IEventBus
    event-type: Keyword
    transform: (event-data) -> transformed-data
    handler: (transformed-data) -> void
    
  Returns:
    unsubscribe-fn"
  [event-bus event-type transform handler]
  (proto/subscribe 
    event-bus event-type
    (fn [event-data]
      (handler (transform event-data)))))

;; ============================================================================
;; Multi-Event Subscription
;; ============================================================================

(defn subscribe-to-multiple
  "Subscribe same handler to multiple event types.
  
  Args:
    event-bus: IEventBus
    event-types: [keyword1 keyword2 ...]
    handler: (event-type event-data) -> void
    
  Returns:
    unsubscribe-all-fn"
  [event-bus event-types handler]
  (let [unsubs (mapv (fn [et]
                       (proto/subscribe event-bus et
                                       (fn [event-data]
                                         (handler et event-data))))
                     event-types)]
    (fn []
      (doseq [unsub unsubs]
        (unsub)))))

;; ============================================================================
;; Debugging and Testing
;; ============================================================================

(defn create-spy-event-bus
  "Create event bus with spy recording all events.
  
  Returns:
    {:bus IEventBus
     :spy-events atom of all events
     :unsubscribe fn}"
  []
  (let [bus (create-event-bus)
        spy-events (atom [])
        event-types [:connect :disconnect :list-update :refresh :error :info]
        unsubs (mapv (fn [et]
                       (proto/subscribe bus et 
                                       (fn [data]
                                         (swap! spy-events conj {:type et :data data}))))
                     event-types)]
    {:bus bus
     :spy-events spy-events
     :unsubscribe (fn []
                    (doseq [unsub unsubs] (unsub)))}))

(defn drain-events
  "Get and clear all recorded events (for testing).
  
  Args:
    spy-events: Atom from create-spy-event-bus
    
  Returns:
    Vector of events"
  [spy-events]
  (let [events @spy-events]
    (reset! spy-events [])
    events))

(defn has-event-type?
  "Check if event type has any subscribers.
  
  Args:
    event-bus: IEventBus
    event-type: Keyword
    
  Returns:
    boolean"
  [event-bus event-type]
  (> (proto/get-handlers-count event-bus event-type) 0))

;; ============================================================================
;; Common Wireless Events
;; ============================================================================

(def WIRELESS-EVENTS
  "Standard wireless event types."
  {:connect :connect          ; User clicks connect button
   :disconnect :disconnect    ; User clicks disconnect button
   :list-update :list-update  ; Server returns updated network list
   :refresh :refresh          ; Refresh button clicked
   :error :error              ; Error occurred
   :info :info})              ; Informational message

(defn publish-connect-event
  "Publish connect request event.
  
  Args:
    event-bus: IEventBus
    network: {:ssid string :encrypted boolean}
    password: string
    
  Returns:
    boolean"
  [event-bus network password]
  (proto/publish event-bus :connect {:network network :password password}))

(defn publish-disconnect-event
  "Publish disconnect request event.
  
  Args:
    event-bus: IEventBus
    
  Returns:
    boolean"
  [event-bus]
  (proto/publish event-bus :disconnect {}))

(defn publish-error-event
  "Publish error event.
  
  Args:
    event-bus: IEventBus
    message: string
    error: optional exception
    
  Returns:
    boolean"
  [event-bus message & [error]]
  (proto/publish event-bus :error {:message message :error error}))
