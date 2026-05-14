(ns cn.li.ac.ability.server.handlers.level-handler
	"Level-up request network handler."
	(:require [cn.li.ac.ability.server.handlers.common :as common]
						[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.server.service.learning :as lrn]
						[cn.li.ac.ability.registry.event :as evt]
						[cn.li.ac.ability.service.player-state :as ps]))

(defn handle-level-up-request
	[_payload player]
	(let [uuid  (uuid/player-uuid-str player)
				state (common/get-state uuid)
				ad    (:ability-data state)
				{:keys [data event]} (lrn/level-up ad uuid)]
		(when data
			(ps/update-ability-data! uuid (constantly data))
			(when event (evt/fire-ability-event! event)))))