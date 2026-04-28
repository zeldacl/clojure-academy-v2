(ns cn.li.forge1201.config.gameplay-bridge
  "Bridge to access ForgeConfigSpec gameplay configuration from Clojure.

  This namespace provides functions to read config values at runtime.
  The actual config is managed by GameplayConfig.java."
  (:import [cn.li.forge1201.config GameplayConfig]))

;; ---------------------------------------------------------------------------
;; Generic settings
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; Ability settings
;; ---------------------------------------------------------------------------

(defn get-normal-metal-blocks []
  (vec (.get GameplayConfig/NORMAL_METAL_BLOCKS)))

(defn get-weak-metal-blocks []
  (vec (.get GameplayConfig/WEAK_METAL_BLOCKS)))

(defn get-metal-entities []
  (vec (.get GameplayConfig/METAL_ENTITIES)))

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
;; CP/Overload data
;; ---------------------------------------------------------------------------

(defn get-cp-recover-cooldown []
  (.get GameplayConfig/CP_RECOVER_COOLDOWN))

(defn get-cp-recover-speed []
  (.get GameplayConfig/CP_RECOVER_SPEED))

(defn get-overload-recover-cooldown []
  (.get GameplayConfig/OVERLOAD_RECOVER_COOLDOWN))

(defn get-overload-recover-speed []
  (.get GameplayConfig/OVERLOAD_RECOVER_SPEED))

(defn get-init-cp-list []
  (vec (.get GameplayConfig/INIT_CP)))

(defn get-add-cp-list []
  (vec (.get GameplayConfig/ADD_CP)))

(defn get-init-overload-list []
  (vec (.get GameplayConfig/INIT_OVERLOAD)))

(defn get-add-overload-list []
  (vec (.get GameplayConfig/ADD_OVERLOAD)))

(defn get-init-cp
  "Get initial CP for a given level (0-5)."
  [level]
  (let [cp-list (get-init-cp-list)]
    (if (and (>= level 0) (< level (count cp-list)))
      (nth cp-list level)
      (last cp-list))))

(defn get-add-cp
  "Get additional CP for a given level (0-5)."
  [level]
  (let [cp-list (get-add-cp-list)]
    (if (and (>= level 0) (< level (count cp-list)))
      (nth cp-list level)
      (last cp-list))))

(defn get-init-overload
  "Get initial overload for a given level (0-5)."
  [level]
  (let [overload-list (get-init-overload-list)]
    (if (and (>= level 0) (< level (count overload-list)))
      (nth overload-list level)
      (last overload-list))))

(defn get-add-overload
  "Get additional overload for a given level (0-5)."
  [level]
  (let [overload-list (get-add-overload-list)]
    (if (and (>= level 0) (< level (count overload-list)))
      (nth overload-list level)
      (last overload-list))))

;; ---------------------------------------------------------------------------
;; Global calculation
;; ---------------------------------------------------------------------------

(defn get-damage-scale []
  (.get GameplayConfig/DAMAGE_SCALE))
