(ns my-mod.platform.nbt
  "Platform-agnostic NBT (Named Binary Tag) abstraction layer.
  
  This namespace provides protocols and factory functions for NBT operations
  without depending on any specific Minecraft version or mod loader.
  
  Platform implementations (forge-1.20.1, forge-1.16.5, fabric-1.20.1) extend
  these protocols to their concrete NBT types and register factory functions
  during platform initialization.
  
  Design:
  - Protocols define operations independent of platform classes
  - Dynamic var *nbt-factory* holds platform-specific constructors
  - Platform code uses extend-type to add protocol implementations
  - Core code uses protocols + factories, never imports net.minecraft classes")

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
  
  (nbt-has-key? [this key]
    "Check if a key exists in the compound."))

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
    "Get element at index. Returns nil if out of bounds."))

;; ============================================================================
;; Platform Factory Registration
;; ============================================================================

(defonce ^{:dynamic true
           :doc "Platform-specific NBT factory functions.
         
         Must be initialized by platform code before core code runs.
         
         Expected keys:
         - :create-compound - fn [] -> INBTCompound
         - :create-list     - fn [] -> INBTList
         
         Example platform initialization:
         (alter-var-root #'my-mod.platform.nbt/*nbt-factory*
           (constantly {:create-compound #(CompoundTag.)
                        :create-list #(ListTag.)}))"}
  *nbt-factory*
  nil)

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
               (nbt-has-key? compound (str k "-int"))
               (nbt-get-int compound (str k "-int"))
               
               (nbt-has-key? compound (str k "-str"))
               (nbt-get-string compound (str k "-str"))
               
               (nbt-has-key? compound (str k "-bool"))
               (nbt-get-boolean compound (str k "-bool"))
               
               (nbt-has-key? compound (str k "-double"))
               (nbt-get-double compound (str k "-double"))
               
               :else
               (nbt-get-tag compound (name k)))])))

(defn factory-initialized?
  "Check if the NBT factory has been initialized by platform code."
  []
  (some? *nbt-factory*))
