(ns cn.li.mcmod.gui.cgui-events
  "Widget event system. Decouples events from widget hierarchy.
   Events are routed by keyword, and handlers are arbitrary functions.")

;; ============================================================================
;; Event Listener Management
;; ============================================================================

(defn listen-widget-event!
  "Register an event handler on a widget.
   :event-klass-or-key - event type (keyword) or Java Class
   :handler - function of arity 1 (receives event)
   Returns widget for chaining.
   
   Multiple handlers can be registered for the same event (all will be called)."
  [widget event-klass-or-key handler]
  (let [event-key (if (keyword? event-klass-or-key)
                    event-klass-or-key
                    (keyword (clojure.string/lower-case (str event-klass-or-key))))]
    (swap! (:events widget) update event-key (fnil conj []) handler)
    widget))

(defn unlisten-widget-event!
  "Unregister all handlers for an event type on this widget.
   Returns widget for chaining."
  [widget event-klass-or-key]
  (let [event-key (if (keyword? event-klass-or-key)
                    event-klass-or-key
                    (keyword (clojure.string/lower-case (str event-klass-or-key))))]
    (swap! (:events widget) dissoc event-key)
    widget))

(defn clear-widget-events!
  "Remove all event handlers from a widget. Returns widget for chaining."
  [widget]
  (reset! (:events widget) {})
  widget)

;; ============================================================================
;; Event Emission
;; ============================================================================

(defn emit-widget-event!
  "Fire an event on a widget. All registered handlers for that event will be called.
   Returns the event object (possibly modified by stop-event-propagation!)."
  [widget event-key event]
  (doseq [handler (get @(:events widget) event-key)]
    (handler event))
  event)

(defn stop-event-propagation!
  "Mark an event as handled/canceled to stop further processing.
   If event is a map, adds :canceled? true; otherwise returns event unchanged."
  [event]
  (if (map? event)
    (assoc event :canceled? true)
    event))

(defn event-canceled? 
  "Check if an event has been marked as canceled."
  [event]
  (boolean (and (map? event) (:canceled? event))))

(defn get-widget-event-handlers
  "Get all handlers registered for a specific event type on this widget."
  [widget event-key]
  (get @(:events widget) event-key []))
