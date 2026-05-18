(ns cn.li.forge1201.config.game-config
  "Gameplay configuration values facade.
  
  Exposes all business-level config value accessors, independent of
  the ForgeConfigSpec implementation detail. This is extracted from
  cn.li.forge1201.config.bridge to maintain separation of concerns:
  
  - bridge.clj: Forge ForgeConfigSpec registration (technical layer)
  - game_config.clj: Business config value access (business layer)"
  (:require [cn.li.mc1201.config.gameplay-bridge :as shared-gameplay])
  (:import [cn.li.forge1201.config GameplayConfig]))

;; =============================================================================
;; Boolean config values
;; =============================================================================

(defn attack-player? []
  (.get GameplayConfig/ATTACK_PLAYER))

(defn destroy-blocks? []
  (.get GameplayConfig/DESTROY_BLOCKS))

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

(def ^:private normal-metal-block?
  (shared-gameplay/list-predicate get-normal-metal-blocks))

(def ^:private weak-metal-block?
  (shared-gameplay/list-predicate get-weak-metal-blocks))

(def ^:private metal-entity?
  (shared-gameplay/list-predicate get-metal-entities))

(defn is-metal-block? [block-id]
  (or (normal-metal-block? block-id)
      (weak-metal-block? block-id)))

(defn is-normal-metal-block? [block-id]
  (normal-metal-block? block-id))

(defn is-weak-metal-block? [block-id]
  (weak-metal-block? block-id))

(defn is-metal-entity? [entity-id]
  (metal-entity? entity-id))

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
  "Get initial CP for a given level, returning 0 for out-of-bounds levels."
  [level]
  (shared-gameplay/level-value (get-init-cp-list) level))

(defn get-add-cp
  "Get additional CP per level, returning 0 for out-of-bounds levels."
  [level]
  (shared-gameplay/level-value (get-add-cp-list) level))

(defn get-init-overload
  "Get initial overload for a given level, returning 0 for out-of-bounds levels."
  [level]
  (shared-gameplay/level-value (get-init-overload-list) level))

(defn get-add-overload
  "Get additional overload per level, returning 0 for out-of-bounds levels."
  [level]
  (shared-gameplay/level-value (get-add-overload-list) level))

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
  (shared-gameplay/make-provider-map
   {:attack-player? attack-player?
    :destroy-blocks? destroy-blocks?
    :get-normal-metal-blocks get-normal-metal-blocks
    :get-weak-metal-blocks get-weak-metal-blocks
    :get-metal-entities get-metal-entities
    :get-cp-recover-cooldown get-cp-recover-cooldown
    :get-cp-recover-speed get-cp-recover-speed
    :get-overload-recover-cooldown get-overload-recover-cooldown
    :get-overload-recover-speed get-overload-recover-speed
    :get-init-cp-list get-init-cp-list
    :get-add-cp-list get-add-cp-list
    :get-init-overload-list get-init-overload-list
    :get-add-overload-list get-add-overload-list
    :get-damage-scale get-damage-scale}))
