(ns cn.li.ac.block.metal-former.config
  "Metal Former configuration")

(def energy-per-tick
  "Energy consumed per tick while forming"
  40.0)

(def max-energy
  "Maximum energy storage"
  15000.0)

(def form-time-ticks
  "Base forming time in ticks"
  150)

(def max-progress
  "Maximum forming progress value"
  100)

(def sync-interval
  "Ticks between GUI sync broadcasts"
  10)
