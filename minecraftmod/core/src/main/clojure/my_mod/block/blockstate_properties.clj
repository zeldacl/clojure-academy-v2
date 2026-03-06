(ns my-mod.block.blockstate-properties
  "Universal BlockState property generation and management system.
  
  Design:
  - Blocks define properties via :block-state-properties in DSL
  - This module generates Minecraft Property objects from definitions
  - Both runtime and datagen use these same objects
  - Adding new properties requires only Clojure changes, not Java changes
  
  Architecture:
  - Property definitions: metadata in Clojure (name, type, range, default)
  - Property objects: generated here as needed
  - Registration: properties stored in registry for runtime/datagen access"
  (:require [my-mod.registry.metadata :as registry-metadata]
            [my-mod.util.log :as log])
  (:import [net.minecraft.world.level.block.state.properties IntegerProperty BooleanProperty]))

;; Property registry - stores all dynamically generated Minecraft Property objects
(defonce property-registry (atom {}))

(defn create-property
  "Create a Minecraft Property object from Clojure definition.
  
  Args:
    property-key: keyword (e.g., :energy, :connected)
    property-config: map with :type, :min/:max (for integer), :name, :default
  
  Returns:
    Property object (IntegerProperty or BooleanProperty)"
  [property-key property-config]
  (let [prop-type (:type property-config)
        prop-name (or (:name property-config) (name property-key))]
    (case prop-type
      :integer (let [min-val (:min property-config 0)
                     max-val (:max property-config 15)]
                 (IntegerProperty/create prop-name min-val max-val))
      :boolean (BooleanProperty/create prop-name)
      (do (log/warn "Unknown property type:" prop-type)
          nil))))

(defn register-block-properties!
  "Register all BlockState properties for a block.
  
  Args:
    block-id: String - DSL block identifier
    block-state-properties: map of property definitions
  
  Side effects:
    - Stores Property objects in property-registry
    - Makes them available for runtime and datagen"
  [block-id block-state-properties]
  (when block-state-properties
    (doseq [[prop-key prop-config] block-state-properties]
      (let [property (create-property prop-key prop-config)]
        (when property
          (swap! property-registry assoc [block-id prop-key] property)
          (log/debug "Registered property" (name prop-key) "for block" block-id))))))

(defn get-property
  "Get a specific property for a block.
  
  Args:
    block-id: String - DSL block identifier
    property-key: keyword - property identifier
  
  Returns:
    Property object or nil"
  [block-id property-key]
  (get @property-registry [block-id property-key]))

(defn get-all-properties
  "Get all properties for a block.
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Vector of Property objects, or empty vector if none"
  [block-id]
  (let [props (filter (fn [[[bid _] _]] (= bid block-id)) @property-registry)]
    (mapv second props)))

(defn get-property-definition
  "Get the Clojure definition of a property for a block.
  
  Args:
    block-id: String - DSL block identifier
    property-key: keyword - property identifier
  
  Returns:
    Map with :type, :min/:max, :name, :default or nil"
  [block-id property-key]
  (let [block-spec (registry-metadata/get-block-spec block-id)
        properties (:block-state-properties block-spec)]
    (get properties property-key)))

(defn init-all-properties!
  "Initialize properties for all blocks with block-state-properties.
  
  Should be called during mod initialization, before Block registration.
  This ensures Property objects are available at all stages."
  []
  (log/info "Initializing BlockState properties...")
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when-let [props (registry-metadata/get-block-state-properties block-id)]
      (register-block-properties! block-id props))))
