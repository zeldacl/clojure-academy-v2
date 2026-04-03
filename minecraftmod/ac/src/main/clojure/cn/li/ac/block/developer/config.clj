(ns cn.li.ac.block.developer.config
  "Developer configuration - ability development multi-block")

;; Developer tiers
(def tiers
  "Developer tier configurations"
  {:normal {:max-energy 100000.0
            :energy-per-tick 100.0
            :development-speed 1.0
            :max-users 1}
   :advanced {:max-energy 200000.0
              :energy-per-tick 150.0
              :development-speed 2.0
              :max-users 2}})

(defn tier-config
  "Get configuration for a specific tier"
  [tier]
  (get tiers tier (:normal tiers)))

;; Structure
(def structure-size
  "3x3x3 cube structure"
  {:width 3 :height 3 :depth 3})

(def validate-interval
  "Ticks between structure validation checks"
  100)

;; Development
(def development-tick-interval
  "Ticks between development progress updates"
  20)

(def sync-interval
  "Ticks between GUI sync broadcasts"
  20)
