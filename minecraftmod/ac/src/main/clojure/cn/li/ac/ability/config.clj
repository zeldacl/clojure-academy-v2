(ns cn.li.ac.ability.config
  "Ability system configuration.

  All numeric balance values live here. Keys map directly to the feature-matrix
  config paths (ac.ability.*). Values are stored in atoms so they can be hot-
  reloaded from config files without restarting.

  NO net.minecraft.* imports - this namespace is platform-neutral."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Global resource configuration (indexed by level-1, so [0]=level1 … [4]=level5)
;; ============================================================================

(def ^:dynamic *init-cp*
  "Initial max CP per ability level (float[5])."
  [100.0 150.0 220.0 320.0 450.0])

(def ^:dynamic *init-overload*
  "Initial max overload per ability level (float[5])."
  [100.0 150.0 220.0 320.0 450.0])

(def ^:dynamic *add-cp*
  "Bonus CP added on top of init-cp per level."
  [0.0 0.0 0.0 0.0 0.0])

(def ^:dynamic *add-overload*
  "Bonus overload per level."
  [0.0 0.0 0.0 0.0 0.0])

;; ============================================================================
;; Recovery configuration
;; ============================================================================

(def ^:dynamic *cp-recover-speed*
  "Base CP recover fraction per tick (multiplied by maxCP).
  Formula: delta = *cp-recover-speed* × maxCP"
  0.0003)

(def ^:dynamic *overload-recover-speed*
  "Base overload recovery amount per tick."
  0.002)

(def ^:dynamic *cp-recover-cooldown*
  "Ticks to wait after CP use before recovery starts."
  100)

(def ^:dynamic *overload-recover-cooldown*
  "Ticks to wait after overload peak before recovery starts."
  60)

;; ============================================================================
;; Progression / experience
;; ============================================================================

(def ^:dynamic *prog-incr-rate*
  "Global multiplier applied to all level experience gains."
  1.0)

;; ============================================================================
;; Pipeline rules (world/game-mode restrictions)
;; ============================================================================

(def ^:dynamic *can-attack-player*
  "Whether ability skills may damage other players."
  true)

(def ^:dynamic *can-destroy-blocks*
  "Global default: whether ability skills may destroy blocks."
  true)

;; ============================================================================
;; Derived helpers
;; ============================================================================

(defn max-cp-for-level
  "Compute max CP for a given level (1-5) by reading config arrays."
  [level]
  {:pre [(>= level 1) (<= level 5)]}
  (let [idx (dec level)]
    (+ (nth *init-cp* idx)
       (nth *add-cp* idx))))

(defn max-overload-for-level
  [level]
  {:pre [(>= level 1) (<= level 5)]}
  (let [idx (dec level)]
    (+ (nth *init-overload* idx)
       (nth *add-overload* idx))))
