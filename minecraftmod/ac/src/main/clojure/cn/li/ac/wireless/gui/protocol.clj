(ns cn.li.ac.wireless.gui.protocol
  "Protocol definitions for wireless GUI system.
  
  These are pure protocol definitions with no implementation,
  enabling clean separation between concerns.
  
  Protocols defined here:
  - IUIComponent: Visual element rendering
  - IStateContainer: State management (no GUI dependency)
  - IEventBus: Event publishing/subscription (independent)
  - IGUIDispatcher: Coordinator of all components"
  )

;; ============================================================================
;; UI Component Protocol
;; ============================================================================

(defprotocol IUIComponent
  "Represents a renderable UI component.
  
  Methods are deliberately simple - actual rendering is delegated
  to platform-specific adapters."
  
  (render [this state event-bus]
    "Render component to UI.
    
    Args:
      state: Current state map
      event-bus: IEventBus for sending events
      
    Returns:
      Rendered UI element (platform-specific)")
  
  (get-layout [this]
    "Get layout metadata.
    
    Returns:
      {:width int :height int :children [...]}")
  
  (on-event [this event-data]
    "Handle user interaction event.
    
    Args:
      event-data: Event details map
      
    Returns:
      true if event was handled"))

;; ============================================================================
;; State Container Protocol
;; ============================================================================

(defprotocol IStateContainer
  "Stateful container **independent from GUI rendering**.
  
  Key design principle: This protocol has NO dependency on IUIComponent.
  It's a pure state holder that can exist and be tested without any UI."
  
  (subscribe-to-changes [this callback]
    "Register callback for state changes.
    
    Args:
      callback: (new-state old-state) -> void
      
    Returns:
      unsubscribe-fn: () -> void to remove callback")
  
  (get-current-state [this]
    "Get current state snapshot.
    
    Returns:
      State map")
  
  (set-state! [this key value]
    "Update single state value.
    
    Args:
      key: State key (keyword)
      value: New value
      
    Returns:
      New state")
  
  (update-state! [this update-map]
    "Update multiple state values.
    
    Args:
      update-map: {:key1 val1 :key2 val2 ...}
      
    Returns:
      New state")
  
  (reset-state! [this]
    "Reset state to initial values.
    
    Returns:
      Initial state"))

;; ============================================================================
;; Event Bus Protocol
;; ============================================================================

(defprotocol IEventBus
  "Event publication/subscription system **independent from UI**.
  
  Key design principle: Pure event bus, no GUI knowledge.
  Can be used in tests or headless environments."
  
  (publish [this event-type event-data]
    "Publish event to subscribers.
    
    Args:
      event-type: Keyword identifying event (e.g., :connect :disconnect)
      event-data: Event details map
      
    Returns:
      true if any handler was called")
  
  (subscribe [this event-type handler]
    "Register handler for event type.
    
    Args:
      event-type: Keyword identifying event
      handler: (event-data) -> void
      
    Returns:
      unsubscribe-fn: () -> void to remove handler")
  
  (unsubscribe-all [this event-type]
    "Remove all handlers for event type.
    
    Args:
      event-type: Keyword identifying event
      
    Returns:
      count of removed handlers")
  
  (get-handlers-count [this event-type]
    "Get number of handlers for event type.
    
    Args:
      event-type: Keyword identifying event
      
    Returns:
      int: Number of handlers"))

;; ============================================================================
;; GUI Dispatcher Protocol
;; ============================================================================

(defprotocol IGUIDispatcher
  "Coordinates state, events, and rendering.
  
  This is the main controller that:
  - Manages state container and event bus
  - Renders components based on state
  - Routes events from UI to handlers"
  
  (render-ui [this]
    "Render all components and return UI tree.
    
    Returns:
      Platform-specific UI tree")
  
  (dispatch-event [this event-type event-data]
    "Process user event.
    
    Args:
      event-type: Keyword identifying event
      event-data: Event details
      
    Returns:
      true if handled")
  
  (set-ui-component [this component-key component]
    "Register UI component.
    
    Args:
      component-key: Keyword identifier
      component: IUIComponent implementation
      
    Returns:
      component")
  
  (get-ui-component [this component-key]
    "Get registered UI component.
    
    Args:
      component-key: Keyword identifier
      
    Returns:
      IUIComponent or nil"))

;; ============================================================================
;; Connection Protocol (Wireless-specific)
;; ============================================================================

(defprotocol IWirelessConnection
  "Represents connection state of a wireless network interface.
  
  This is wireless-specific (not generic GUI)."
  
  (get-connected-network [this]
    "Get connected network SSID or nil.
    
    Returns:
      string: SSID or nil if not connected")
  
  (is-connected? [this]
    "Check if currently connected.
    
    Returns:
      boolean")
  
  (get-available-networks [this]
    "Get list of available networks.
    
    Returns:
      Vector of {:ssid string :encrypted boolean ...}")
  
  (connect! [this ssid password]
    "Attempt connection to network.
    
    Args:
      ssid: Network name
      password: Password (empty string if none)
      
    Returns:
      {:success boolean :reason string}")
  
  (disconnect! [this]
    "Disconnect from current network.
    
    Returns:
      {:success boolean}")
  
  (refresh-networks! [this]
    "Refresh available networks list.
    
    Returns:
      Vector of available networks"))
