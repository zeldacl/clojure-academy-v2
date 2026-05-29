(ns cn.li.ac.ability.server.service.learning-runtime
	"Runtime-side learning mutations.

	The pure learning service computes the updated ability-data and event payload.
	This namespace owns writing the result back into player-state and publishing
	the lifecycle event exactly once."
	(:require [cn.li.ac.ability.service.command-runtime :as command-rt]))

(defn learn-skill!
	"Learn `skill-id` for `uuid`, persist the updated ability-data, and fire the
	skill-learn lifecycle event when the skill is newly learned.

	Returns {:data updated-ability-data :event event-or-nil}."
	[uuid skill-id]
	(let [result (command-rt/run-command! uuid {:command :learn-skill
																		 :skill-id skill-id
																		 :check-conditions? false})
				next-state (:state result)
				event (first (:events result))]
		{:data (:ability-data next-state)
		 :event event}))