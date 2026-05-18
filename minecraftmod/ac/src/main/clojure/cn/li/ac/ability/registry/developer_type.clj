(ns cn.li.ac.ability.registry.developer-type
  "Developer-type rules extracted from skill.clj.

  Developer type represents the minimum hardware tier a player needs to
  use a skill.  Mirrors the original Java enum ordering:
    :portable < :normal < :advanced"
  (:require [cn.li.ac.ability.domain.developer :as developer]))

;; ============================================================================
;; Rank table
;; ============================================================================

(def order developer/developer-order)

;; ============================================================================
;; Public API
;; ============================================================================

(defn min-for-level
  "Return the minimum developer type required for a given skill level."
  [level]
  (developer/min-for-level level))

(defn gte?
  "True when developer-type `a` is at least as powerful as `b`."
  [a b]
  (developer/gte? a b))
