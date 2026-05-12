(ns cn.li.forge1201.config.game-config
  "Gameplay configuration values facade.
  
  Exposes all business-level config value accessors, independent of
  the ForgeConfigSpec implementation detail. This is extracted from
  cn.li.forge1201.config.bridge to maintain separation of concerns:
  
  - bridge.clj: Forge ForgeConfigSpec registration (technical layer)
  - game_config.clj: Business config value access (business layer)"
  (:import [cn.li.forge1201.config GameplayConfig]))

;; =============================================================================
;; Boolean config values
;; =============================================================================

(defn analysis-enabled? []
  (.get GameplayConfig/ANALYSIS_ENABLED))

(defn attack-player? []
  (.get GameplayConfig/ATTACK_PLAYER))

(defn destroy-blocks? []
  (.get GameplayConfig/DESTROY_BLOCKS))

(defn gen-ores? []
  (.get GameplayConfig/GEN_ORES))

(defn gen-phase-liquid? []
  (.get GameplayConfig/GEN_PHASE_LIQUID))

(defn heads-or-tails? []
  (.get GameplayConfig/HEADS_OR_TAILS))

;; =============================================================================
;; Block and entity config lists
;; =============================================================================

(defn get-normal-metal-blocks []
  (vec (.get GameplayConfig/NORMAL_METAL_BLOCKS)))

(defn get-weak-metal-blocks []
  (vec (.get GameplayConfig/WEAK_METAL_BLOCKS)))

(defn get-metal-entities []
  (vec (.get GameplayConfig/METAL_ENTITIES)))

;; =============================================================================
;; Block and entity predicates
;; =============================================================================

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

;; =============================================================================
;; CP (Combat Power) recovery config
;; =============================================================================

(defn get-cp-recover-cooldown []
  (.get GameplayConfig/CP_RECOVER_COOLDOWN))

(defn get-cp-recover-speed []
  (.get GameplayConfig/CP_RECOVER_SPEED))

;; =============================================================================
;; Overload recovery config
;; =============================================================================

(defn get-overload-recover-cooldown []
  (.get GameplayConfig/OVERLOAD_RECOVER_COOLDOWN))

(defn get-overload-recover-speed []
  (.get GameplayConfig/OVERLOAD_RECOVER_SPEED))

;; =============================================================================
;; CP and overload level lists
;; =============================================================================

(defn get-init-cp-list []
  (vec (.get GameplayConfig/INIT_CP)))

(defn get-add-cp-list []
  (vec (.get GameplayConfig/ADD_CP)))

(defn get-init-overload-list []
  (vec (.get GameplayConfig/INIT_OVERLOAD)))

(defn get-add-overload-list []
  (vec (.get GameplayConfig/ADD_OVERLOAD)))

;; =============================================================================
;; Level-indexed CP and overload accessors
;; =============================================================================

(defn get-init-cp
  "Get initial CP for a given level, with fallback to last value for out-of-bounds levels"
  [level]
  (let [cp-list (get-init-cp-list)]
    (if (and (>= level 0) (< level (count cp-list)))
      (nth cp-list level)
      (last cp-list))))

(defn get-add-cp
  "Get additional CP per level, with fallback to last value for out-of-bounds levels"
  [level]
  (let [cp-list (get-add-cp-list)]
    (if (and (>= level 0) (< level (count cp-list)))
      (nth cp-list level)
      (last cp-list))))

(defn get-init-overload
  "Get initial overload for a given level, with fallback to last value for out-of-bounds levels"
  [level]
  (let [overload-list (get-init-overload-list)]
    (if (and (>= level 0) (< level (count overload-list)))
      (nth overload-list level)
      (last overload-list))))

(defn get-add-overload
  "Get additional overload per level, with fallback to last value for out-of-bounds levels"
  [level]
  (let [overload-list (get-add-overload-list)]
    (if (and (>= level 0) (< level (count overload-list)))
      (nth overload-list level)
      (last overload-list))))

;; =============================================================================
;; Damage config
;; =============================================================================

(defn get-damage-scale []
  (.get GameplayConfig/DAMAGE_SCALE))

;; =============================================================================
;; Config provider map
;; Used by shared layer to bind gameplay-config protocol
;; =============================================================================

(defn provider-map
  "Return map of all config accessors for shared layer protocol binding"
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
