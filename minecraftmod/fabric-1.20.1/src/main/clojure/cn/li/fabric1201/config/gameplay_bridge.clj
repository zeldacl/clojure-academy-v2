(ns cn.li.fabric1201.config.gameplay-bridge
  "Bridge to access Fabric gameplay configuration from Clojure (stub).

  This namespace provides functions to read config values at runtime.
  For Fabric, this is a stub that returns default values.

  A full implementation would require creating a FabricGameplayConfig.java
  or using Fabric's modconfig library for configuration."
  (:require [cn.li.mcmod.util.log :as log]))

;; Note: This is a stub implementation. Full Fabric config support would require:
;; 1. Creating a FabricGameplayConfig.java class similar to Forge's GameplayConfig
;; 2. Or integrating with Fabric's ModConfig API
;; 3. Loading config from fabric.mod.json or a custom config file

;; ---------------------------------------------------------------------------
;; Generic settings (Stub - returns defaults)
;; ---------------------------------------------------------------------------

(defn analysis-enabled? []
  ;; TODO: Load from Fabric config
  true)

(defn attack-player? []
  true)

(defn destroy-blocks? []
  true)

(defn gen-ores? []
  true)

(defn gen-phase-liquid? []
  true)

(defn heads-or-tails? []
  true)

;; ---------------------------------------------------------------------------
;; Ability settings (Stub - returns defaults)
;; ---------------------------------------------------------------------------

(defn get-normal-metal-blocks []
  ;; TODO: Load from Fabric config
  [])

(defn get-weak-metal-blocks []
  [])

(defn get-metal-entities []
  [])

(defn is-metal-block? [block-id]
  (let [normal (set (get-normal-metal-blocks))
        weak (set (get-weak-metal-blocks))]
    (or (contains? normal block-id)
        (contains? weak block-id))))

(defn is-normal-metal-block? [block-id]
  (contains? (set (get-normal-metal-blocks)) block-id))

(defn is-weak-metal-block? [block-id]
  (contains? (set (get-weak-metal-blocks)) block-id))

(defn is-metal-entity? [entity-id]
  (contains? (set (get-metal-entities)) entity-id))

;; ---------------------------------------------------------------------------
;; CP/Overload data (Stub - returns defaults)
;; ---------------------------------------------------------------------------

(defn get-cp-recover-cooldown []
  ;; TODO: Load from Fabric config
  100)

(defn get-cp-recover-speed []
  1.0)

(defn get-overload-recover-cooldown []
  200)

(defn get-overload-recover-speed []
  0.5)

(defn get-init-cp-list []
  ;; Default CP initialization by level
  [])

(defn get-add-cp-list []
  ;; Default CP addition by level
  [])

(defn get-init-overload-list []
  ;; Default overload initialization by level
  [])

(defn get-add-overload-list []
  ;; Default overload addition by level
  [])

(defn get-init-cp
  "Get initial CP for a specific level."
  [level]
  (if-let [cp-list (seq (get-init-cp-list))]
    (get cp-list level 0)
    0))

(defn get-add-cp
  "Get CP addition for a specific level."
  [level]
  (if-let [cp-list (seq (get-add-cp-list))]
    (get cp-list level 0)
    0))

(defn get-init-overload
  "Get initial overload for a specific level."
  [level]
  (if-let [ov-list (seq (get-init-overload-list))]
    (get ov-list level 0)
    0))

(defn get-add-overload
  "Get overload addition for a specific level."
  [level]
  (if-let [ov-list (seq (get-add-overload-list))]
    (get ov-list level 0)
    0))

;; ---------------------------------------------------------------------------
;; Damage settings
;; ---------------------------------------------------------------------------

(defn get-damage-scale []
  ;; TODO: Load from Fabric config
  1.0)

;; ============================================================================
;; Initialization (Fabric Stub)
;; ============================================================================

(defn init-fabric-gameplay-config!
  "Initialize Fabric gameplay configuration (stub).

  For full implementation, this should load config from a Fabric config file."
  []
  (log/info "Fabric gameplay config initialized (stub - using default values)")
  nil)

(defn provider-map
  []
  {:analysis-enabled? analysis-enabled?
   :attack-player? attack-player?
   :destroy-blocks? destroy-blocks?
   :gen-ores? gen-ores?
   :gen-phase-liquid? gen-phase-liquid?
   :heads-or-tails? heads-or-tails?
   :get-normal-metal-blocks get-normal-metal-blocks
   :get-weak-metal-blocks get-weak-metal-blocks
   :get-metal-entities get-metal-entities
   :is-normal-metal-block? is-normal-metal-block?
   :is-weak-metal-block? is-weak-metal-block?
   :is-metal-block? is-metal-block?
   :is-metal-entity? is-metal-entity?
   :get-cp-recover-cooldown get-cp-recover-cooldown
   :get-cp-recover-speed get-cp-recover-speed
   :get-overload-recover-cooldown get-overload-recover-cooldown
   :get-overload-recover-speed get-overload-recover-speed
   :get-init-cp get-init-cp
   :get-add-cp get-add-cp
   :get-init-overload get-init-overload
   :get-add-overload get-add-overload
   :get-damage-scale get-damage-scale})
