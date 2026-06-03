(ns cn.li.mcmod.platform.nbt
  "Platform-agnostic NBT (Named Binary Tag) abstraction layer."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

;; ============================================================================
;; NBT Compound Protocol
;; ============================================================================

(defprotocol INBTCompound
  "Protocol for NBT compound tag operations (key-value storage)"
  
  (nbt-set-int! [this key value]
    "Set an integer value. Returns the compound for chaining.")
  
  (nbt-get-int [this key]
    "Get an integer value. Returns 0 if key doesn't exist.")
  
  (nbt-set-string! [this key value]
    "Set a string value. Returns the compound for chaining.")
  
  (nbt-get-string [this key]
    "Get a string value. Returns empty string if key doesn't exist.")
  
  (nbt-set-boolean! [this key value]
    "Set a boolean value. Returns the compound for chaining.")
  
  (nbt-get-boolean [this key]
    "Get a boolean value. Returns false if key doesn't exist.")
  
  (nbt-set-double! [this key value]
    "Set a double value. Returns the compound for chaining.")
  
  (nbt-get-double [this key]
    "Get a double value. Returns 0.0 if key doesn't exist.")
  
  (nbt-set-tag! [this key tag]
    "Set a nested NBT tag (compound or list). Returns the compound for chaining.")
  
  (nbt-get-tag [this key]
    "Get a nested NBT tag. Returns nil if key doesn't exist.")
  
    (nbt-get-compound [this key]
      "Get a nested compound tag. Returns compound or nil if key doesn't exist.")
  
  (nbt-get-list [this key]
    "Get a nested list tag. Returns list or nil if key doesn't exist.")
  
  (nbt-has-key? [this key]
    "Check if a key exists in the compound.")

  (nbt-set-float! [this key value]
    "Set a float value. Returns the compound for chaining.")

  (nbt-get-float [this key]
    "Get a float value. Returns 0.0f if key doesn't exist.")

  (nbt-set-long! [this key value]
    "Set a long value. Returns the compound for chaining.")

  (nbt-get-long [this key]
    "Get a long value. Returns 0L if key doesn't exist."))

;; ============================================================================
;; NBT List Protocol
;; ============================================================================

(defprotocol INBTList
  "Protocol for NBT list tag operations (ordered collection)"
  
  (nbt-append! [this element]
    "Append an element to the list. Returns the list for chaining.")
  
  (nbt-list-size [this]
    "Get the number of elements in the list.")
  
  (nbt-list-get [this index]
    "Get element at index. Returns nil if out of bounds.")
  
  (nbt-list-get-compound [this index]
    "Get compound tag at index. Returns compound or nil if out of bounds."))

;; ============================================================================
;; Platform Factory Registration
;; ============================================================================

(def ^:private ^:dynamic *nbt-factory* nil)
(def ^:private ^:dynamic *nbt-has-key-fn* nil)

(defn install-nbt-factory!
  [factory-map label]
  (prt/install-impl! #'*nbt-factory* factory-map (or label "nbt-factory")))

(defn install-nbt-has-key-fn!
  [f label]
  (prt/install-impl! #'*nbt-has-key-fn* f (or label "nbt-has-key")))

(defn call-with-nbt-factory [factory-map f]
  (binding [*nbt-factory* factory-map] (f)))

(defn call-with-nbt-runtime
  "Test/install helper binding factory map and optional has-key fn."
  [{:keys [factory has-key-fn]} f]
  (binding [*nbt-factory* factory
            *nbt-has-key-fn* has-key-fn]
    (f)))

;; ============================================================================
;; Factory Functions
;; ============================================================================

(defn create-nbt-compound
  "Create a new empty NBT compound tag.
  
  Requires platform to be initialized (*nbt-factory* must be set).
  
  Returns: INBTCompound implementation from current platform
  Throws: ex-info if platform not initialized"
  []
  (if-let [factory *nbt-factory*]
    (if-let [create-fn (:create-compound factory)]
      (create-fn)
      (throw (ex-info "NBT factory missing :create-compound function"
                      {:factory factory})))
    (throw (ex-info "NBT factory not initialized - platform must call init-platform! first"
                    {:hint "Check that platform mod initialization calls platform-impl/init-platform!"}))))

(defn create-nbt-list
  "Create a new empty NBT list tag.
  
  Requires platform to be initialized (*nbt-factory* must be set).
  
  Returns: INBTList implementation from current platform
  Throws: ex-info if platform not initialized"
  []
  (if-let [factory *nbt-factory*]
    (if-let [create-fn (:create-list factory)]
      (create-fn)
      (throw (ex-info "NBT factory missing :create-list function"
                      {:factory factory})))
    (throw (ex-info "NBT factory not initialized - platform must call init-platform! first"
                    {:hint "Check that platform mod initialization calls platform-impl/init-platform!"}))))

(defn nbt-has-key-safe?
  "Check whether key exists in NBT compound.

Uses platform override when installed; otherwise falls back to protocol dispatch."
  [this key]
  (if-let [f *nbt-has-key-fn*]
    (boolean (f this key))
    (nbt-has-key? this key)))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn nbt-compound-to-map
  "Convert an NBT compound to a Clojure map (shallow conversion).
  
  Only converts primitive types directly stored in the compound.
  Nested compounds/lists remain as NBT objects."
  [compound keys]
  (into {}
        (for [k keys]
          [k (cond
               (nbt-has-key-safe? compound (str k "-int"))
               (nbt-get-int compound (str k "-int"))
               
               (nbt-has-key-safe? compound (str k "-str"))
               (nbt-get-string compound (str k "-str"))
               
               (nbt-has-key-safe? compound (str k "-bool"))
               (nbt-get-boolean compound (str k "-bool"))
               
               (nbt-has-key-safe? compound (str k "-double"))
               (nbt-get-double compound (str k "-double"))
               
               :else
               (nbt-get-tag compound (name k)))])))

(defn factory-initialized?
  "Check if the NBT factory has been initialized by platform code."
  []
  (some? *nbt-factory*))
