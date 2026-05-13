(ns cn.li.ac.ability.registry.learning-cost
  "Learning cost formula extracted from skill.clj.

  Formula: cost = 3 + level² × 0.5
  This matches the original Java getLearningStims() implementation.")

(defn learning-cost
  "Return the stim cost to learn a skill at `level` (1-5)."
  [level]
  (+ 3.0 (* level level 0.5)))
