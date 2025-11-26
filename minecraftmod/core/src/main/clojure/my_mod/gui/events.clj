(ns my-mod.gui.events
  "LambdaLib2 GuiEventBus wrapper - Event handling DSL"
  (:import [cn.lambdalib2.cgui.event GuiEventBus GuiEvent IGuiEventHandler
            LeftClickEvent RightClickEvent MouseClickEvent
            KeyEvent DragEvent DragStopEvent
            GainFocusEvent LostFocusEvent
            FrameEvent RefreshEvent AddWidgetEvent]
           [cn.lambdalib2.cgui Widget]))

;; ============================================================================
;; Event Listener Registration
;; ============================================================================

(defn listen!
  "Register an event listener on a widget
  
  Args:
  - widget: Widget instance
  - event-class: Event class (e.g. LeftClickEvent)
  - handler: Function (fn [event] ...) that handles the event
  
  Returns: the widget (for chaining)
  
  Example:
  (listen! widget LeftClickEvent
    (fn [event]
      (println \"Clicked at\" (.x event) (.y event))))"
  [^Widget widget event-class handler]
  (let [event-handler (reify IGuiEventHandler
                        (handleEvent [_ event]
                          (handler event)))]
    (.regEventHandler widget event-class event-handler))
  widget)

(defn unlisten!
  "Unregister event listener(s) from widget
  
  Args:
  - widget: Widget instance
  - event-class: Event class to remove (optional, removes all if nil)"
  ([^Widget widget]
   (.clearEventHandlers widget)
   widget)
  ([^Widget widget event-class]
   (.unregEventHandler widget event-class)
   widget))

;; ============================================================================
;; Event Handler Constructors (DSL Style)
;; ============================================================================

(defn on-left-click
  "Create left-click event handler
  
  Handler function receives event with:
  - .x .y: mouse position
  - .w .h: widget size
  
  Example:
  (on-left-click widget
    (fn [e] (println \"Left clicked at\" (.x e) (.y e))))"
  [widget handler]
  (listen! widget LeftClickEvent handler))

(defn on-right-click
  "Create right-click event handler"
  [widget handler]
  (listen! widget RightClickEvent handler))

(defn on-key-press
  "Create keyboard event handler
  
  Handler receives event with:
  - .keyCode: int keycode
  - .typedChar: char typed"
  [widget handler]
  (listen! widget KeyEvent handler))

(defn on-drag
  "Create drag event handler
  
  Handler receives event with:
  - .dx .dy: drag delta
  - .x .y: current position"
  [widget handler]
  (listen! widget DragEvent handler))

(defn on-drag-stop
  "Create drag-stop event handler"
  [widget handler]
  (listen! widget DragStopEvent handler))

(defn on-gain-focus
  "Create focus-gained event handler"
  [widget handler]
  (listen! widget GainFocusEvent handler))

(defn on-lost-focus
  "Create focus-lost event handler"
  [widget handler]
  (listen! widget LostFocusEvent handler))

(defn on-frame
  "Create frame event handler (called every frame)
  
  Handler receives event with:
  - .partialTicks: float for smooth animation"
  [widget handler]
  (listen! widget FrameEvent handler))

(defn on-refresh
  "Create refresh event handler (called when GUI needs update)"
  [widget handler]
  (listen! widget RefreshEvent handler))

(defn on-widget-added
  "Create widget-added event handler"
  [widget handler]
  (listen! widget AddWidgetEvent handler))

;; ============================================================================
;; Event Chaining DSL
;; ============================================================================

(defn with-events
  "Attach multiple event handlers to a widget
  
  Args:
  - widget: Widget instance
  - events: Map of event-class -> handler-fn
  
  Returns: the widget
  
  Example:
  (with-events widget
    {LeftClickEvent (fn [e] (println \"Left\"))
     RightClickEvent (fn [e] (println \"Right\"))
     KeyEvent (fn [e] (println \"Key:\" (.keyCode e)))})"
  [widget events]
  (doseq [[event-class handler] events]
    (listen! widget event-class handler))
  widget)

(defmacro events->
  "Thread-first macro for event registration
  
  Example:
  (events-> widget
    (on-left-click #(println \"Click\"))
    (on-key-press #(println \"Key\"))
    (on-frame #(println \"Frame\")))"
  [widget & forms]
  `(-> ~widget ~@forms))

;; ============================================================================
;; Common Event Handlers
;; ============================================================================

(defn make-click-handler
  "Create a simple click handler that calls f with no args"
  [f]
  (fn [_event] (f)))

(defn make-hover-handler
  "Create hover handler that sets a state atom"
  [hovered-atom]
  {:enter (fn [_] (reset! hovered-atom true))
   :exit (fn [_] (reset! hovered-atom false))})

(defn make-text-input-handler
  "Create text input handler for TextBox
  
  Args:
  - text-atom: atom to store text
  - validator: (optional) fn to validate input
  
  Returns: KeyEvent handler"
  [text-atom & {:keys [validator]}]
  (fn [event]
    (let [char (.typedChar event)
          keycode (.keyCode event)
          current @text-atom]
      (cond
        ;; Backspace
        (= keycode 14) 
        (when (pos? (count current))
          (reset! text-atom (subs current 0 (dec (count current)))))
        
        ;; Printable character
        (and (>= keycode 2) (<= keycode 53) (not= char \u0000))
        (let [new-text (str current char)]
          (when (or (nil? validator) (validator new-text))
            (reset! text-atom new-text)))))))

;; ============================================================================
;; Event Utilities
;; ============================================================================

(defn stop-propagation!
  "Stop event from propagating to parent widgets"
  [^GuiEvent event]
  (.setCanceled event true))

(defn event-pos
  "Extract [x y] position from mouse event"
  [event]
  [(.-x event) (.-y event)])

(defn event-in-bounds?
  "Check if event position is within bounds"
  [event x y w h]
  (let [[ex ey] (event-pos event)]
    (and (>= ex x) (< ex (+ x w))
         (>= ey y) (< ey (+ y h)))))
