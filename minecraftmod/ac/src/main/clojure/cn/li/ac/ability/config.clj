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
  [1800.0 2800.0 4000.0 5800.0 8000.0])

(def ^:dynamic *init-overload*
  "Initial max overload per ability level (float[5])."
  [100.0 150.0 240.0 350.0 500.0])

(def ^:dynamic *add-cp*
  "Max growth ceiling for CP on top of init-cp per level."
  [900.0 1000.0 1500.0 1700.0 12000.0])

(def ^:dynamic *add-overload*
  "Max growth ceiling for overload on top of init-overload per level."
  [40.0 70.0 80.0 100.0 500.0])

;; ============================================================================
;; Recovery configuration
;; ============================================================================

(def ^:dynamic *cp-recover-speed*
  "Multiplier for CP recovery formula.
  Formula: delta = speed × 0.0003 × maxCP × lerp(1, 2, curCP/maxCP)"
  1.0)

(def ^:dynamic *overload-recover-speed*
  "Multiplier for overload recovery formula.
  Formula: delta = speed × max(0.002×maxOL, 0.007×maxOL×lerp(1, 0.5, curOL/maxOL/2))"
  1.0)

(def ^:dynamic *cp-recover-cooldown*
  "Ticks to wait after CP use before recovery starts."
  15)

(def ^:dynamic *overload-recover-cooldown*
  "Ticks to wait after overload peak before recovery starts."
  32)

;; ============================================================================
;; Growth rates (max-cp / max-overload grow on perform)
;; ============================================================================

(def ^:dynamic *maxcp-incr-rate*
  "Fraction of consumed CP that is added to add-max-cp on each perform.
  Original: 0.0025"
  0.0025)

(def ^:dynamic *maxo-incr-rate*
  "Fraction of overload that is added to add-max-overload on each perform.
  Original: 0.0058"
  0.0058)

;; ============================================================================
;; Damage scaling
;; ============================================================================

(def ^:dynamic *damage-scale*
  "Global damage multiplier applied to all ability skill damage."
  1.0)

;; ============================================================================
;; Context runtime defaults (used when skill spec omits per-skill values)
;; ============================================================================

(def ^:dynamic *runtime-cp-consume-per-tick*
  "Default CP consume base used by context key-tick runtime."
  1.0)

(def ^:dynamic *runtime-overload-per-tick*
  "Default overload add base used by context key-tick runtime."
  0.6)

(def ^:dynamic *runtime-main-cooldown-ticks*
  "Default main cooldown ticks applied on key-up when skill has no override."
  20)

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
