(ns cn.li.ac.block.ability-interferer.config
  "Ability Interferer configuration")

;; Range settings
(def min-range
  "Minimum interference range (blocks)"
  10.0)

(def max-range
  "Maximum interference range (blocks)"
  100.0)

(def default-range
  "Default interference range (blocks)"
  20.0)

;; Energy consumption
(def base-energy-per-tick
  "Base energy consumption per tick"
  10.0)

(def energy-per-player-per-tick
  "Additional energy per affected player per tick"
  5.0)

(def energy-per-range-unit
  "Energy cost multiplier per range unit"
  0.1)

(def max-energy
  "Maximum energy storage"
  50000.0)

;; Operation
(def check-interval
  "Ticks between player detection checks"
  20)

(def sync-interval
  "Ticks between GUI sync broadcasts"
  20)

(defn calculate-energy-cost
  "Calculate energy cost based on range and player count"
  [range player-count]
  (+ base-energy-per-tick
     (* energy-per-range-unit range)
     (* energy-per-player-per-tick player-count)))
