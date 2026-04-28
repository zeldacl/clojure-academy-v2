(ns cn.li.ac.config.gameplay
  "Gameplay configuration for AcademyCraft.

  This namespace provides access to gameplay configuration values.
  In production, values are loaded from ForgeConfigSpec (forge-1.20.1 module).
  During development/testing, default values are used.

  No Minecraft imports."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Config Bridge (injected by platform layer)
;; ============================================================================

(def ^:dynamic *config-bridge*
  "Dynamic var bound to config bridge implementation by platform layer.
  If nil, uses default values."
  nil)

;; ============================================================================
;; Default Configuration (fallback when *config-bridge* is nil)
;; ============================================================================
(def default-generic-config
  {:analysis true
   :attack-player true
   :destroy-blocks true
   :worlds-whitelisted-destroying-blocks []
   :use-mouse-wheel false
   :gen-ores true
   :gen-phase-liquid true
   :give-cloud-terminal true
   :heads-or-tails false
   :font "Microsoft YaHei"})

;; Ability Configuration
(def default-ability-config
  {:normal-metal-blocks
   ["minecraft:rail"
    "minecraft:iron_bars"
    "minecraft:iron_block"
    "minecraft:activator_rail"
    "minecraft:detector_rail"
    "minecraft:golden_rail"
    "minecraft:sticky_piston"
    "minecraft:piston"
    "minecraft:iron_door"
    "minecraft:iron_trapdoor"
    "minecraft:heavy_weighted_pressure_plate"
    "minecraft:anvil"
    "minecraft:chipped_anvil"
    "minecraft:damaged_anvil"
    "minecraft:hopper"
    "minecraft:cauldron"]

   :weak-metal-blocks
   ["minecraft:dispenser"
    "minecraft:hopper"
    "minecraft:iron_ore"
    "minecraft:deepslate_iron_ore"
    "minecraft:raw_iron_block"]

   :metal-entities
   ["minecraft:minecart"
    "minecraft:chest_minecart"
    "minecraft:furnace_minecart"
    "minecraft:tnt_minecart"
    "minecraft:hopper_minecart"
    "minecraft:spawner_minecart"
    "minecraft:command_block_minecart"
    "my_mod:entity_mag_hook"
    "minecraft:iron_golem"]})

;; CP and Overload Data
(def default-cp-overload-data
  {:cp-recover-cooldown 15
   :cp-recover-speed 1.0
   :overload-recover-cooldown 32
   :overload-recover-speed 1.0

   :maxcp-incr-rate 0.0025
   :maxo-incr-rate 0.0058
   :prog-incr-rate 1.0

   ;; Level data (6 levels: 0-5)
   :init-cp [1800 1800 2800 4000 5800 8000]
   :add-cp [0 900 1000 1500 1700 12000]
   :init-overload [100 100 150 240 350 500]
   :add-overload [0 40 70 80 100 500]})

;; Global Calculation Parameters
(def default-calc-global
  {:damage-scale 1.0})

;; ============================================================================
;; Configuration Access Functions
;; ============================================================================

(defn- use-bridge?
  "Check if config bridge is available."
  []
  (some? *config-bridge*))

(defn init-config!
  "Initialize configuration. No-op when using config bridge."
  []
  (when-not (use-bridge?)
    (log/info "Using default gameplay configuration (no config bridge)")))

;; Generic Config
(defn analysis-enabled? []
  (if (use-bridge?)
    ((:analysis-enabled? *config-bridge*))
    (:analysis default-generic-config)))

(defn attack-player-enabled? []
  (if (use-bridge?)
    ((:attack-player? *config-bridge*))
    (:attack-player default-generic-config)))

(defn destroy-blocks-enabled? []
  (if (use-bridge?)
    ((:destroy-blocks? *config-bridge*))
    (:destroy-blocks default-generic-config)))

(defn gen-ores-enabled? []
  (if (use-bridge?)
    ((:gen-ores? *config-bridge*))
    (:gen-ores default-generic-config)))

(defn gen-phase-liquid-enabled? []
  (if (use-bridge?)
    ((:gen-phase-liquid? *config-bridge*))
    (:gen-phase-liquid default-generic-config)))

(defn heads-or-tails-enabled? []
  (if (use-bridge?)
    ((:heads-or-tails? *config-bridge*))
    (:heads-or-tails default-generic-config)))

;; Ability Config
(defn get-normal-metal-blocks []
  (if (use-bridge?)
    ((:get-normal-metal-blocks *config-bridge*))
    (:normal-metal-blocks default-ability-config)))

(defn get-weak-metal-blocks []
  (if (use-bridge?)
    ((:get-weak-metal-blocks *config-bridge*))
    (:weak-metal-blocks default-ability-config)))

(defn get-metal-entities []
  (if (use-bridge?)
    ((:get-metal-entities *config-bridge*))
    (:metal-entities default-ability-config)))

(defn is-normal-metal-block?
  "Check if a block ID is a normal metal block."
  [block-id]
  (if (use-bridge?)
    ((:is-normal-metal-block? *config-bridge*) block-id)
    (some #(= % block-id) (get-normal-metal-blocks))))

(defn is-weak-metal-block?
  "Check if a block ID is a weak metal block."
  [block-id]
  (if (use-bridge?)
    ((:is-weak-metal-block? *config-bridge*) block-id)
    (some #(= % block-id) (get-weak-metal-blocks))))

(defn is-metal-block?
  "Check if a block ID is any metal block."
  [block-id]
  (if (use-bridge?)
    ((:is-metal-block? *config-bridge*) block-id)
    (or (is-normal-metal-block? block-id)
        (is-weak-metal-block? block-id))))

(defn is-metal-entity?
  "Check if an entity ID is a metal entity."
  [entity-id]
  (if (use-bridge?)
    ((:is-metal-entity? *config-bridge*) entity-id)
    (some #(= % entity-id) (get-metal-entities))))

;; CP/Overload Config
(defn get-cp-recover-cooldown []
  (if (use-bridge?)
    ((:get-cp-recover-cooldown *config-bridge*))
    (:cp-recover-cooldown default-cp-overload-data)))

(defn get-cp-recover-speed []
  (if (use-bridge?)
    ((:get-cp-recover-speed *config-bridge*))
    (:cp-recover-speed default-cp-overload-data)))

(defn get-overload-recover-cooldown []
  (if (use-bridge?)
    ((:get-overload-recover-cooldown *config-bridge*))
    (:overload-recover-cooldown default-cp-overload-data)))

(defn get-overload-recover-speed []
  (if (use-bridge?)
    ((:get-overload-recover-speed *config-bridge*))
    (:overload-recover-speed default-cp-overload-data)))

(defn get-init-cp
  "Get initial CP for a level (0-5)."
  [level]
  (if (use-bridge?)
    ((:get-init-cp *config-bridge*) level)
    (nth (:init-cp default-cp-overload-data) level 0)))

(defn get-add-cp
  "Get additional CP for a level (0-5)."
  [level]
  (if (use-bridge?)
    ((:get-add-cp *config-bridge*) level)
    (nth (:add-cp default-cp-overload-data) level 0)))

(defn get-init-overload
  "Get initial overload for a level (0-5)."
  [level]
  (if (use-bridge?)
    ((:get-init-overload *config-bridge*) level)
    (nth (:init-overload default-cp-overload-data) level 0)))

(defn get-add-overload
  "Get additional overload for a level (0-5)."
  [level]
  (if (use-bridge?)
    ((:get-add-overload *config-bridge*) level)
    (nth (:add-overload default-cp-overload-data) level 0)))

;; Global Calculation
(defn get-damage-scale []
  (if (use-bridge?)
    ((:get-damage-scale *config-bridge*))
    (:damage-scale default-calc-global)))

;; ============================================================================
;; Configuration Validation
;; ============================================================================

(defn validate-config!
  "Validate configuration values."
  []
  (let [errors (atom [])]
    ;; Validate CP/Overload arrays have 6 elements
    (when (not= 6 (count (:init-cp default-cp-overload-data)))
      (swap! errors conj "init-cp must have 6 elements"))
    (when (not= 6 (count (:add-cp default-cp-overload-data)))
      (swap! errors conj "add-cp must have 6 elements"))
    (when (not= 6 (count (:init-overload default-cp-overload-data)))
      (swap! errors conj "init-overload must have 6 elements"))
    (when (not= 6 (count (:add-overload default-cp-overload-data)))
      (swap! errors conj "add-overload must have 6 elements"))

    ;; Validate positive values
    (when (<= (get-cp-recover-speed) 0)
      (swap! errors conj "cp-recover-speed must be positive"))
    (when (<= (get-overload-recover-speed) 0)
      (swap! errors conj "overload-recover-speed must be positive"))
    (when (<= (get-damage-scale) 0)
      (swap! errors conj "damage-scale must be positive"))

    (if (empty? @errors)
      (log/info "Configuration validation passed")
      (do
        (log/error "Configuration validation failed:" @errors)
        (throw (ex-info "Invalid configuration" {:errors @errors}))))))
