(ns cn.li.ac.block.phase-gen.config
  "Phase Generator configuration - generates energy from imaginary phase liquid")

;; Energy generation
(def base-generation-rate
  "Base energy generation per tick when consuming liquid"
  30.0)

(def liquid-consumption-rate
  "Liquid consumption per tick (in millibuckets)"
  10)

(def energy-per-liquid
  "Energy generated per millibucket of liquid"
  3.0)

;; Energy storage
(def max-energy
  "Maximum energy storage"
  80000.0)

;; Structure validation
(def validate-interval
  "Ticks between liquid source checks"
  40)

;; Sync interval
(def sync-interval
  "Ticks between GUI sync broadcasts"
  20)

;; Liquid detection
(def check-radius
  "Radius to check for liquid sources (blocks)"
  2)

(defn calculate-generation-rate
  "Calculate actual generation rate based on available liquid"
  [liquid-available]
  (if (>= liquid-available liquid-consumption-rate)
    (* liquid-consumption-rate energy-per-liquid)
    0.0))
