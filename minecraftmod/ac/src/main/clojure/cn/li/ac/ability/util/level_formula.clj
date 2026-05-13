(ns cn.li.ac.ability.util.level-formula
	"Shared formula helpers for ability progression thresholds.")

(defn level-up-threshold
	"Compute EXP threshold for next level.

	Args:
	- skill-count: number of controllable skills at current level
	- all-mastered?: true when all those skills are mastered
	- category-rate: category-specific progression multiplier
	- global-rate: global progression multiplier"
	[skill-count all-mastered? category-rate global-rate]
	(let [base (* skill-count 1.333 category-rate global-rate)]
		(if all-mastered?
			(* base 0.5)
			base)))