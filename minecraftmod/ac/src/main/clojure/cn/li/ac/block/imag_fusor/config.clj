(ns cn.li.ac.block.imag-fusor.config
  "Imaginary Fusor configuration - crafting machine")

;; Energy consumption
(def energy-per-tick
  "Energy consumed per tick while crafting"
  50.0)

(def max-energy
  "Maximum energy storage"
  20000.0)

;; Crafting
(def craft-time-ticks
  "Base crafting time in ticks (default recipe)"
  200)

(def max-progress
  "Maximum crafting progress value"
  100)

;; Inventory slots
(def input-slot-count
  "Number of input slots"
  2)

(def output-slot-count
  "Number of output slots"
  1)

(def energy-slot-index
  "Index of energy item slot"
  3)

;; Sync interval
(def sync-interval
  "Ticks between GUI sync broadcasts"
  10)
