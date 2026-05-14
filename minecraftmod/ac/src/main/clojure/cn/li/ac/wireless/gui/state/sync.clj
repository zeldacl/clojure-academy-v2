(ns cn.li.ac.wireless.gui.state.sync
  "State synchronization container for wireless GUI.
  
  Key design principle: This is **pure state management** with **NO GUI dependency**.
  
  - Can be tested without any GUI framework
  - Can be used in headless scenarios
  - Subscribers are called on state changes
  - Thread-safe atomic updates"
  (:require [cn.li.ac.wireless.gui.protocol :as proto]
            [cn.li.ac.foundation.concurrency :as conc]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; State Container Implementation
;; ============================================================================

(defn create-state-container
  "Create a new state container with initial state.
  
  Args:
    initial-state: Map of initial state values
    
  Returns:
    Reified IStateContainer implementation"
  [initial-state]
  (let [state-atom (atom initial-state)
        callbacks-atom (atom [])
        initial-copy (atom initial-state)]
    
    (reify proto/IStateContainer
      
      (subscribe-to-changes [this callback]
        "Register callback for state changes.
        
        Returns unsubscribe function."
        (let [callback-id (gensym "cb-")]
          (swap! callbacks-atom conj {:id callback-id :fn callback})
          (fn []
            (swap! callbacks-atom 
                   (fn [cbs] (filterv #(not= (:id %) callback-id) cbs))))))
      
      (get-current-state [this]
        "Get current state snapshot."
        (conc/atomic-get state-atom "state-container-read"))
      
      (set-state! [this key value]
        "Update single state value and notify subscribers."
        (let [old-state @state-atom
              new-state (assoc old-state key value)]
          (reset! state-atom new-state)
          (doseq [{:keys [fn]} @callbacks-atom]
            (try
              (fn new-state old-state)
              (catch Exception ex
                (log/error (str "Error in state change callback: " ex)))))
          new-state))
      
      (update-state! [this update-map]
        "Update multiple state values and notify subscribers."
        (let [old-state @state-atom
              new-state (merge old-state update-map)]
          (reset! state-atom new-state)
          (doseq [{:keys [fn]} @callbacks-atom]
            (try
              (fn new-state old-state)
              (catch Exception ex
                (log/error (str "Error in state change callback: " ex)))))
          new-state))
      
      (reset-state! [this]
        "Reset state to initial values."
        (let [old-state @state-atom
              new-state @initial-copy]
          (reset! state-atom new-state)
          (doseq [{:keys [fn]} @callbacks-atom]
            (try
              (fn new-state old-state)
              (catch Exception ex
                (log/error (str "Error in state change callback: " ex)))))
          new-state)))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn sync-state-getter
  "Create a function that gets a specific key from state.
  
  Args:
    state-container: IStateContainer
    key: State key (keyword)
    
  Returns:
    Function that gets current value of key"
  [state-container key]
  (fn []
    (get (proto/get-current-state state-container) key)))

(defn sync-state-setter
  "Create a function that sets a specific key in state.
  
  Args:
    state-container: IStateContainer
    key: State key (keyword)
    
  Returns:
    Function that sets value and returns new state"
  [state-container key]
  (fn [value]
    (proto/set-state! state-container key value)))

(defn sync-state-updater
  "Create a function that updates state with map.
  
  Args:
    state-container: IStateContainer
    
  Returns:
    Function that takes map and updates state"
  [state-container]
  (fn [update-map]
    (proto/update-state! state-container update-map)))

;; ============================================================================
;; Batch Operations
;; ============================================================================

(defn batch-set-state!
  "Set multiple state values in single update.
  
  Args:
    state-container: IStateContainer
    updates: {:key1 val1 :key2 val2 ...}
    
  Returns:
    New state"
  [state-container updates]
  (proto/update-state! state-container updates))

(defn get-state-snapshot
  "Get read-only snapshot of current state.
  
  Args:
    state-container: IStateContainer
    
  Returns:
    Immutable state map"
  [state-container]
  (-> (proto/get-current-state state-container)
      (into [])))

;; ============================================================================
;; Observation Helpers
;; ============================================================================

(defn on-state-change
  "Subscribe to any state change (convenience wrapper).
  
  Args:
    state-container: IStateContainer
    handler: (new-state old-state) -> void
    
  Returns:
    unsubscribe-fn"
  [state-container handler]
  (proto/subscribe-to-changes state-container handler))

(defn on-key-change
  "Subscribe to changes of specific key.
  
  Args:
    state-container: IStateContainer
    key: State key
    handler: (new-value old-value) -> void
    
  Returns:
    unsubscribe-fn"
  [state-container key handler]
  (proto/subscribe-to-changes 
    state-container
    (fn [new-state old-state]
      (let [old-val (get old-state key)
            new-val (get new-state key)]
        (when (not= old-val new-val)
          (handler new-val old-val))))))

;; ============================================================================
;; Testing Helpers
;; ============================================================================

(defn create-test-state-container
  "Create state container for testing with spy capabilities.
  
  Returns:
    {:container IStateContainer
     :spy-changes atom of all changes
     :unsubscribe fn}"
  [initial-state]
  (let [container (create-state-container initial-state)
        spy-changes (atom [])
        unsub (proto/subscribe-to-changes 
                container 
                (fn [new-state old-state]
                  (swap! spy-changes conj {:new new-state :old old-state})))]
    {:container container
     :spy-changes spy-changes
     :unsubscribe unsub}))

(defn drain-changes
  "Get and clear all recorded changes (for testing).
  
  Args:
    spy-changes: Atom from create-test-state-container
    
  Returns:
    Vector of changes"
  [spy-changes]
  (let [changes @spy-changes]
    (reset! spy-changes [])
    changes))
