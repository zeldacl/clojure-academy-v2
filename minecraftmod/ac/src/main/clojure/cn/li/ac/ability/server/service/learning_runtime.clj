(ns cn.li.ac.ability.server.service.learning-runtime
	"Runtime-side learning mutations.

	The pure learning service computes the updated ability-data and event payload.
	This namespace owns writing the result back into player-state and publishing
	the lifecycle event exactly once."
	(:require [cn.li.ac.ability.server.service.learning :as learning]
						[cn.li.ac.ability.service.player-state :as ps]
						[cn.li.ac.ability.registry.event :as evt]))

(defn learn-skill!
	"Learn `skill-id` for `uuid`, persist the updated ability-data, and fire the
	skill-learn lifecycle event when the skill is newly learned.

	Returns {:data updated-ability-data :event event-or-nil}."
	[uuid skill-id]
	(let [state (or (ps/get-player-state uuid)
									(ps/get-or-create-player-state! uuid))
				{:keys [data event]} (learning/learn-skill (:ability-data state) uuid skill-id)]
		(when event
			(ps/update-ability-data! uuid (constantly data))
			(evt/fire-ability-event! event))
		{:data data
		 :event event}))