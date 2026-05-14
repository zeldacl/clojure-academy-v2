(ns cn.li.ac.testing.mocks
  "Mock object factories for AC module testing.
  
  Provides convenient constructors for creating test doubles
  and mocking dependencies."
  (:require [cn.li.ac.foundation.vblock :as vb]
            [cn.li.ac.wireless.gui.protocol :as proto]))

;; ============================================================================
;; Mock Builders (Builder Pattern)
;; ============================================================================

(defn vblock-builder
  "Build a VBlock with fluent API.
  
  Returns:
    {:build fn :x fn :y fn :z fn :type fn :ignore-chunk fn}"
  []
  (let [state (atom {:x 0 :y 64 :z 0 :type :node :ignore-chunk false})]
    {:build (fn [] (vb/vblock
                    (:x @state) (:y @state) (:z @state)
                    (:type @state) (:ignore-chunk @state)))
     :x (fn [v] (swap! state assoc :x v))
     :y (fn [v] (swap! state assoc :y v))
     :z (fn [v] (swap! state assoc :z v))
     :type (fn [v] (swap! state assoc :type v))
     :ignore-chunk (fn [v] (swap! state assoc :ignore-chunk v))}))

;; ============================================================================
;; Mock Protocols
;; ============================================================================

(defn mock-ui-component
  "Create mock IUIComponent.
  
  Args:
    opts: {:render-result value :layout map}
    
  Returns:
    Mock IUIComponent"
  [& [{:keys [render-result layout]
       :or {render-result nil layout {:width 256 :height 256}}}]]
  (let [render-calls (atom [])
        layout-calls (atom [])
        event-calls (atom [])
        component (reify proto/IUIComponent
                    (render [this state event-bus]
                      (swap! render-calls conj {:state state :event-bus event-bus})
                      render-result)
                    (get-layout [this]
                      (swap! layout-calls conj {})
                      layout)
                    (on-event [this event-data]
                      (swap! event-calls conj event-data)
                      true)

                    Object
                    (toString [this]
                      (str "MockUIComponent{renders:" (count @render-calls) "}")))]
    {:component component
     :render-calls render-calls
     :layout-calls layout-calls
     :event-calls event-calls}))

(defn mock-state-container
  "Create mock IStateContainer for testing.
  
  Returns:
    Mock IStateContainer"
  []
  (let [state-atom (atom {})
        set-calls (atom [])
        update-calls (atom [])
        container (reify proto/IStateContainer
                    (subscribe-to-changes [this callback]
                      (fn [] nil))

                    (get-current-state [this]
                      @state-atom)

                    (set-state! [this key value]
                      (swap! set-calls conj {:key key :value value})
                      (swap! state-atom assoc key value))

                    (update-state! [this update-map]
                      (swap! update-calls conj update-map)
                      (swap! state-atom merge update-map))

                    (reset-state! [this]
                      (reset! state-atom {})))]

    {:container container
     :set-calls set-calls
     :update-calls update-calls
     :state state-atom}))

(defn mock-event-bus
  "Create mock IEventBus for testing.
  
  Returns:
    Mock IEventBus"
  []
  (let [publish-calls (atom [])
        subscribe-calls (atom [])
        subscribers (atom {})
        bus (reify proto/IEventBus
              (publish [this event-type event-data]
                (swap! publish-calls conj {:type event-type :data event-data})
                true)

              (subscribe [this event-type handler]
                (swap! subscribe-calls conj {:type event-type :handler handler})
                (fn [] nil))

              (unsubscribe-all [this event-type]
                (let [count (count (get @subscribers event-type []))]
                  (swap! subscribers dissoc event-type)
                  count))

              (get-handlers-count [this event-type]
                (count (get @subscribers event-type []))))]

    {:bus bus
     :publish-calls publish-calls
     :subscribe-calls subscribe-calls
     :subscribers subscribers}))

(defn mock-gui-dispatcher
  "Create mock IGUIDispatcher for testing.
  
  Returns:
    Mock IGUIDispatcher"
  []
  (let [render-calls (atom [])
        dispatch-calls (atom [])
        dispatcher (reify proto/IGUIDispatcher
                     (render-ui [this]
                       (swap! render-calls conj {})
                       :mock-ui)

                     (dispatch-event [this event-type event-data]
                       (swap! dispatch-calls conj {:type event-type :data event-data})
                       true)

                     (set-ui-component [this component-key component]
                       component)

                     (get-ui-component [this component-key]
                       nil))]

    {:dispatcher dispatcher
     :render-calls render-calls
     :dispatch-calls dispatch-calls}))

(defn mock-wireless-connection
  "Create mock IWirelessConnection for testing.
  
  Returns:
    Mock IWirelessConnection"
  []
  (let [state-atom (atom {:connected-network nil
                          :available-networks []})
        connection (reify proto/IWirelessConnection
                     (get-connected-network [this]
                       (:connected-network @state-atom))

                     (is-connected? [this]
                       (some? (:connected-network @state-atom)))

                     (get-available-networks [this]
                       (:available-networks @state-atom))

                     (connect! [this ssid password]
                       (swap! state-atom assoc :connected-network ssid)
                       {:success true :reason "OK"})

                     (disconnect! [this]
                       (swap! state-atom assoc :connected-network nil)
                       {:success true})

                     (refresh-networks! [this]
                       (:available-networks @state-atom)))]

    {:connection connection
     :state state-atom}))

;; ============================================================================
;; Verification Helpers
;; ============================================================================

(defn verify-called
  "Verify a mock was called N times.
  
  Args:
    call-log: Atom of calls from mock
    count: Expected number of calls
    message: Optional message
    
  Returns:
    true if count matches"
  [call-log expected-count & [message]]
  (let [actual-count (count @call-log)]
    (if (= actual-count expected-count)
      true
      (throw (AssertionError.
               (or message
                   (str "Expected " expected-count " calls, got " actual-count)))))))

(defn verify-called-with
  "Verify a mock was called with specific arguments.
  
  Args:
    call-log: Atom of calls from mock
    matcher: Predicate or expected value
    
  Returns:
    true if any call matches"
  [call-log matcher]
  (if (some (if (fn? matcher) matcher #(= matcher %)) @call-log)
    true
    (throw (AssertionError. (str "No calls matched " matcher)))))

(defn verify-not-called
  "Verify a mock was never called.
  
  Args:
    call-log: Atom of calls from mock
    
  Returns:
    true if not called"
  [call-log]
  (if (empty? @call-log)
    true
    (throw (AssertionError. (str "Expected no calls, got " (count @call-log))))))

;; ============================================================================
;; Common Test Scenarios
;; ============================================================================

(defn setup-connected-network
  "Setup mock with connected network state.
  
  Args:
    connection-mock: From mock-wireless-connection
    ssid: Network SSID
    networks: List of available networks
    
  Returns:
    Updated connection mock"
  [connection-mock ssid networks]
  (let [state (:state connection-mock)]
    (swap! state assoc
           :connected-network ssid
           :available-networks networks))
  connection-mock)

(defn setup-empty-networks
  "Setup mock with no networks available.
  
  Args:
    connection-mock: From mock-wireless-connection
    
  Returns:
    Updated connection mock"
  [connection-mock]
  (let [state (:state connection-mock)]
    (swap! state assoc :available-networks []))
  connection-mock)

(defn setup-multiple-networks
  "Setup mock with multiple available networks.
  
  Args:
    connection-mock: From mock-wireless-connection
    networks: [{:ssid \"...\" :encrypted boolean} ...]
    
  Returns:
    Updated connection mock"
  [connection-mock networks]
  (let [state (:state connection-mock)]
    (swap! state assoc :available-networks networks))
  connection-mock)

;; ============================================================================
;; Capture Helpers
;; ============================================================================

(defn capture-calls
  "Capture all calls to a mock and return them.
  
  Args:
    mock-result: Result from mock-* function
    
  Returns:
    Map of all captured calls"
  [mock-result]
  (reduce (fn [acc [key val]]
            (if (instance? clojure.lang.IAtom val)
              (assoc acc key @val)
              acc))
          {}
          mock-result))

(defn print-mock-state
  "Print debug information about mock state.
  
  Args:
    mock-result: Result from mock-* function
    
  Returns:
    nil"
  [mock-result]
  (doseq [[key val] mock-result]
    (when (instance? clojure.lang.IAtom val)
      (println (str key ": " @val)))))
