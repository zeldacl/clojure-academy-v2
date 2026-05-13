(ns cn.li.ac.ability.registry.developer-type
  "Developer-type rules extracted from skill.clj.

  Developer type represents the minimum hardware tier a player needs to
  use a skill.  Mirrors the original Java enum ordering:
    :portable < :normal < :advanced")

;; ============================================================================
;; Rank table
;; ============================================================================

(def order [:portable :normal :advanced])

(def ^:private rank
  {:portable 0
   :normal   1
   :advanced 2})

;; ============================================================================
;; Public API
;; ============================================================================

(defn min-for-level
  "Return the minimum developer type required for a given skill level."
  [level]
  (cond
    (<= level 2) :portable
    (= level 3)  :normal
    :else        :advanced))

(defn gte?
  "True when developer-type `a` is at least as powerful as `b`."
  [a b]
  (>= (long (get rank a -1))
      (long (get rank b -1))))
