(ns cn.li.ac.ability.registry.learning-cost
  "Learning cost formula extracted from skill.clj.

  Formula: cost = 3 + level² × 0.5
  This matches the original Java getLearningStims() implementation."
  (:require [cn.li.ac.ability.config :as cfg]))

(defn learning-cost
  "Return the stim cost to learn a skill at `level` (1-5)."
  [level]
    (+ (cfg/skill-learning-cost-base)
      (* level level (cfg/skill-learning-cost-level-square-factor))))
