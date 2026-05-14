(ns cn.li.ac.foundation.stateful
  "Stateful object pattern utilities.
  
  Provides infrastructure for building state machines and stateful objects
  with consistent:
  - State transitions
  - Lifecycle management
  - Disposal/cleanup
  - Validity checking"
  (:require [cn.li.ac.foundation.concurrency :as conc]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; State Machine Protocol
;; ============================================================================

(defprotocol IStateful
  "Protocol for stateful objects with lifecycle."
  (get-state [this] "Get current state")
  (set-state! [this state] "Set state to value")
  (valid? [this] "Check if object is still valid")
  (dispose! [this] "Cleanup and invalidate object")
  (is-disposed? [this] "Check if object has been disposed"))

;; ============================================================================
;; Stateful Record Factory
;; ============================================================================

(defn create-stateful
  "Create a stateful object with automatic lifecycle management.
  
  Args:
    initial-state: Initial state value
    opts: {:valid-states [s1 s2 ...]
           :on-state-change (fn [old-state new-state])
           :on-dispose (fn [])}
    
  Returns:
    Stateful object satisfying IStateful protocol"
  [initial-state {:keys [valid-states on-state-change on-dispose]}]
  (let [state-atom (atom initial-state)
        disposed-atom (atom false)]
    (reify IStateful
      (get-state [this]
        @state-atom)
      
      (set-state! [this new-state]
        (when @disposed-atom
          (throw (ex-info "Cannot set state on disposed object" {})))
        (let [old-state @state-atom]
          (if valid-states
            (if (and (contains? valid-states old-state)
                     (contains? valid-states new-state))
              (do
                (reset! state-atom new-state)
                (when on-state-change
                  (on-state-change old-state new-state))
                true)
              (do
                (log/warn (str "Invalid state transition: " old-state " -> " new-state))
                false))
            (do
              (reset! state-atom new-state)
              (when on-state-change
                (on-state-change old-state new-state))
              true))))
      
      (valid? [this]
        (not @disposed-atom))
      
      (dispose! [this]
        (when-not @disposed-atom
          (reset! disposed-atom true)
          (when on-dispose
            (on-dispose))))
      
      (is-disposed? [this]
        @disposed-atom))))

;; ============================================================================
;; Def Macro for Stateful Record Types
;; ============================================================================

(defmacro defstateful
  "Define a stateful record type with lifecycle.
  
  Syntax:
    (defstateful MyStatefulThing
      [:field1 :field2]
      {:initial-state :created
       :valid-states #{:created :running :disposed}
       :on-state-change (fn [old new] ...)
       :on-dispose (fn [...] ...)})
  
  Generates:
    - MyStatefulThing record
    - create-MyStatefulThing factory
    - IStateful protocol implementation"
  [name fields opts]
  (let [factory-name (symbol (str "create-" name))
        fields-with-state (into fields [:state :disposed?])]
    `(do
       (defrecord ~name ~fields-with-state)
       
       (defn ~factory-name
         [~@(remove #(or (= % :state) (= % :disposed?)) fields) & opts#]
         (let [opts# (if (seq opts#) (first opts#) ~opts)]
           (->~name
             ~@(map (fn [f] f) (remove #(or (= % :state) (= % :disposed?)) fields))
             (:initial-state opts# :created)
             false))))))

;; ============================================================================
;; State Transition Helpers
;; ============================================================================

(defn transition!
  "Transition state machine safely with validation.
  
  Args:
    stateful: IStateful object
    to-state: Target state
    
  Returns:
    boolean: true if transition succeeded"
  [stateful to-state]
  (if (valid? stateful)
    (set-state! stateful to-state)
    false))

(defn ensure-valid!
  "Check that object is valid, throw if disposed.
  
  Args:
    stateful: IStateful object
    message (optional): Error message
    
  Returns:
    stateful: The same object
    
  Throws:
    ex-info if object is disposed"
  ([stateful]
   (ensure-valid! stateful "Object has been disposed"))
  ([stateful message]
   (if (valid? stateful)
     stateful
     (throw (ex-info message {:disposed? (is-disposed? stateful)})))))

(defn with-state-lock
  "Execute function with guaranteed state validity.
  
  Args:
    stateful: IStateful object
    f: Function to execute
    
  Returns:
    Result of function
    
  Throws:
    ex-info if object is disposed"
  [stateful f]
  (ensure-valid! stateful)
  (f stateful))

;; ============================================================================
;; Lifecycle Management
;; ============================================================================

(defn auto-dispose-on-error
  "Wrap a function to dispose stateful object on error.
  
  Args:
    stateful: IStateful object
    f: Function that may throw
    
  Returns:
    Result of function or throws ex-info"
  [stateful f]
  (try
    (f stateful)
    (catch Exception ex
      (dispose! stateful)
      (throw (ex-info "Error in stateful operation" 
                      {:error (.getMessage ex) :disposed true} 
                      ex)))))

(defn run-with-lifecycle
  "Execute function with automatic setup/teardown.
  
  Args:
    setup-fn: () -> IStateful
    f: (stateful) -> result
    
  Returns:
    Result of function"
  [setup-fn f]
  (let [stateful (setup-fn)]
    (try
      (f stateful)
      (finally
        (dispose! stateful)))))

;; ============================================================================
;; Batch State Updates
;; ============================================================================

(defn batch-state-update!
  "Update multiple stateful objects atomically.
  
  If any update fails, rollback is attempted.
  
  Args:
    statefuls: [obj1 obj2 ...]
    update-fn: (obj) -> new-state
    
  Returns:
    {:success boolean :count int :failed [obj]}"
  [statefuls update-fn]
  (let [old-states (mapv get-state statefuls)]
    (try
      (doseq [s statefuls]
        (set-state! s (update-fn (get-state s))))
      {:success true :count (count statefuls) :failed []}
      (catch Exception ex
        (log/warn "Batch update failed, attempting rollback...")
        (doseq [[s old-state] (map vector statefuls old-states)]
          (try
            (set-state! s old-state)
            (catch Exception _ nil)))
        {:success false :count 0 :failed statefuls}))))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn all-valid?
  "Check if all stateful objects are valid.
  
  Args:
    statefuls: [obj1 obj2 ...] or variadic
    
  Returns:
    boolean"
  [& statefuls]
  (every? valid? statefuls))

(defn filter-disposed
  "Filter out disposed objects from collection.
  
  Args:
    statefuls: [obj1 obj2 ...]
    
  Returns:
    Vector of valid objects"
  [statefuls]
  (filterv valid? statefuls))

(defn cleanup-all!
  "Dispose all stateful objects in collection.
  
  Args:
    statefuls: [obj1 obj2 ...]
    
  Returns:
    Count of disposed objects"
  [statefuls]
  (let [count (count statefuls)]
    (doseq [s statefuls]
      (when (valid? s)
        (dispose! s)))
    count))
