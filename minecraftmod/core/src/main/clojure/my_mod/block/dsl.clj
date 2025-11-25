(ns my-mod.block.dsl
  "Block DSL - Declarative block definition using Clojure macros"
  (:require [my-mod.util.log :as log]))

;; Block Registry - stores all defined blocks
(defonce block-registry (atom {}))

;; Block specifications
(defrecord BlockSpec [id material hardness resistance light-level requires-tool
                      sounds harvest-level harvest-tool friction slip-factor
                      on-right-click on-break on-place properties])

;; Material types (version-agnostic)
(def materials
  {:stone :stone
   :wood :wood
   :metal :metal
   :glass :glass
   :dirt :dirt
   :sand :sand
   :grass :grass
   :leaves :leaves
   :water :water
   :lava :lava
   :air :air})

;; Sound types
(def sound-types
  {:stone :stone
   :wood :wood
   :metal :metal
   :glass :glass
   :grass :grass
   :sand :sand
   :gravel :gravel})

;; Tool types
(def tool-types
  {:pickaxe :pickaxe
   :axe :axe
   :shovel :shovel
   :hoe :hoe
   :sword :sword})

;; Default values
(def default-hardness 1.5)
(def default-resistance 6.0)
(def default-light-level 0)
(def default-friction 0.6)

;; Create block specification
(defn create-block-spec
  "Create a block specification from options"
  [block-id options]
  (map->BlockSpec
    {:id block-id
     :material (or (:material options) :stone)
     :hardness (or (:hardness options) default-hardness)
     :resistance (or (:resistance options) default-resistance)
     :light-level (or (:light-level options) default-light-level)
     :requires-tool (or (:requires-tool options) false)
     :sounds (or (:sounds options) :stone)
     :harvest-level (or (:harvest-level options) 0)
     :harvest-tool (or (:harvest-tool options) :pickaxe)
     :friction (or (:friction options) default-friction)
     :slip-factor (or (:slip-factor options) default-friction)
     :on-right-click (or (:on-right-click options) (fn [_] nil))
     :on-break (or (:on-break options) (fn [_] nil))
     :on-place (or (:on-place options) (fn [_] nil))
     :properties (or (:properties options) {})}))

;; Validate block specification
(defn validate-block-spec [block-spec]
  (when-not (:id block-spec)
    (throw (ex-info "Block must have an :id" {:spec block-spec})))
  (when-not (string? (:id block-spec))
    (throw (ex-info "Block :id must be a string" {:id (:id block-spec)})))
  (when-not (get materials (:material block-spec))
    (throw (ex-info "Invalid material" {:material (:material block-spec)
                                        :valid materials})))
  true)

;; Register block in registry
(defn register-block! [block-spec]
  (validate-block-spec block-spec)
  (log/info "Registering block:" (:id block-spec))
  (swap! block-registry assoc (:id block-spec) block-spec)
  block-spec)

;; Get block from registry
(defn get-block [block-id]
  (get @block-registry block-id))

;; List all registered blocks
(defn list-blocks []
  (keys @block-registry))

;; Main macro: defblock
(defmacro defblock
  "Define a block with declarative syntax
  
  Example:
  (defblock my-block
    :material :stone
    :hardness 3.0
    :resistance 10.0
    :light-level 15
    :requires-tool true
    :harvest-tool :pickaxe
    :harvest-level 2
    :on-right-click (fn [data] (println \"Clicked!\")))"
  [block-name & options]
  (let [block-id (name block-name)
        options-map (apply hash-map options)]
    `(def ~block-name
       (register-block!
         (create-block-spec ~block-id ~options-map)))))

;; Helper: create ore block preset
(defn ore-preset
  "Create an ore block preset with common properties"
  [harvest-level]
  {:material :stone
   :hardness 3.0
   :resistance 3.0
   :requires-tool true
   :harvest-tool :pickaxe
   :harvest-level harvest-level
   :sounds :stone})

;; Helper: create wood block preset
(defn wood-preset
  "Create a wood block preset with common properties"
  []
  {:material :wood
   :hardness 2.0
   :resistance 3.0
   :requires-tool false
   :harvest-tool :axe
   :harvest-level 0
   :sounds :wood})

;; Helper: create metal block preset
(defn metal-preset
  "Create a metal block preset with common properties"
  [harvest-level]
  {:material :metal
   :hardness 5.0
   :resistance 6.0
   :requires-tool true
   :harvest-tool :pickaxe
   :harvest-level harvest-level
   :sounds :metal})

;; Helper: create glass block preset
(defn glass-preset
  "Create a glass block preset with common properties"
  []
  {:material :glass
   :hardness 0.3
   :resistance 0.3
   :requires-tool false
   :sounds :glass})

;; Helper: create light-emitting block
(defn light-block-preset
  "Create a light-emitting block preset"
  [light-level]
  {:material :glass
   :hardness 1.0
   :resistance 1.0
   :light-level light-level
   :sounds :glass})

;; Helper: combine presets
(defn merge-presets
  "Merge multiple presets with options"
  [& preset-and-options]
  (apply merge preset-and-options))

;; Multimethod for version-specific block creation
(def ^:dynamic *forge-version* nil)

(defmulti create-platform-block
  "Create a version-specific block instance"
  (fn [_block-spec] *forge-version*))

(defmethod create-platform-block :default [block-spec]
  (throw (ex-info "No block implementation for version"
                  {:version *forge-version*
                   :block-id (:id block-spec)})))

;; Block interaction handlers
(defn handle-right-click
  "Handle right-click on a block"
  [block-spec event-data]
  (when-let [handler (:on-right-click block-spec)]
    (handler event-data)))

(defn handle-break
  "Handle block break"
  [block-spec event-data]
  (when-let [handler (:on-break block-spec)]
    (handler event-data)))

(defn handle-place
  "Handle block placement"
  [block-spec event-data]
  (when-let [handler (:on-place block-spec)]
    (handler event-data)))

;; Get block properties for platform creation
(defn get-block-properties
  "Get block properties map for platform-specific creation"
  [block-spec]
  {:material (:material block-spec)
   :hardness (:hardness block-spec)
   :resistance (:resistance block-spec)
   :light-level (:light-level block-spec)
   :requires-tool (:requires-tool block-spec)
   :sounds (:sounds block-spec)
   :harvest-level (:harvest-level block-spec)
   :harvest-tool (:harvest-tool block-spec)
   :friction (:friction block-spec)
   :slip-factor (:slip-factor block-spec)})
