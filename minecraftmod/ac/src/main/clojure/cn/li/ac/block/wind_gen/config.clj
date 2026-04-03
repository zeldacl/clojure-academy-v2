(ns cn.li.ac.block.wind-gen.config
  "Wind Generator configuration - height-based energy generation")

;; Energy generation rates
(def base-generation-rate
  "Base energy generation per tick at optimal height"
  20.0)

(def optimal-height-min
  "Minimum Y-level for optimal generation"
  120)

(def optimal-height-max
  "Maximum Y-level (higher doesn't increase generation)"
  256)

(def min-height
  "Minimum Y-level for any generation"
  64)

(def height-multiplier-curve
  "Generation multiplier based on height
   Below min-height: 0%
   min-height to optimal-min: linear scale 0% to 100%
   optimal-min to optimal-max: 100%
   Above optimal-max: 100%"
  {:min-height min-height
   :optimal-min optimal-height-min
   :optimal-max optimal-height-max})

;; Energy storage
(def max-energy-main
  "Maximum energy storage for main generator"
  50000.0)

(def max-energy-base
  "Maximum energy storage for base block"
  100000.0)

;; Structure validation
(def validate-interval
  "Ticks between structure validation checks"
  100)

;; Sync interval
(def sync-interval
  "Ticks between GUI sync broadcasts"
  20)

;; Wind speed simulation
(def wind-variation-enabled?
  "Enable random wind speed variations"
  true)

(def wind-variation-min
  "Minimum wind speed multiplier (70%)"
  0.7)

(def wind-variation-max
  "Maximum wind speed multiplier (130%)"
  1.3)

(def wind-change-interval
  "Ticks between wind speed changes"
  200)

(defn calculate-height-multiplier
  "Calculate generation multiplier based on Y-level"
  [y-level]
  (cond
    (< y-level min-height) 0.0
    (< y-level optimal-height-min)
    (let [range (- optimal-height-min min-height)
          progress (- y-level min-height)]
      (/ (double progress) (double range)))
    :else 1.0))

(defn calculate-generation-rate
  "Calculate actual generation rate based on height and wind speed"
  [y-level wind-multiplier]
  (* base-generation-rate
     (calculate-height-multiplier y-level)
     wind-multiplier))
