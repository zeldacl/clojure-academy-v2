(ns cn.li.ac.block.cat-engine.config
  "Cat Engine configuration - automatic wireless linking")

;; Search parameters
(def search-radius
  "Radius to search for wireless nodes (blocks)"
  16.0)

(def search-interval
  "Ticks between node search attempts"
  100)

(def link-cooldown
  "Ticks to wait after failed link attempt"
  200)

;; Link behavior
(def auto-link-enabled?
  "Enable automatic linking to nearby nodes"
  true)

(def prefer-closest?
  "Prefer closest node over random selection"
  false)

(def max-link-attempts
  "Maximum link attempts before cooldown"
  3)
