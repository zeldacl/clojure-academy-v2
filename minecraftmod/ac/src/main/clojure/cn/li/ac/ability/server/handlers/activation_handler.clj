(ns cn.li.ac.ability.server.handlers.activation-handler
	"Activation toggle request network handler."
	(:require [cn.li.ac.ability.server.handlers.common :as common]
						[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.server.service.resource :as res]
						[cn.li.ac.ability.registry.event :as evt]
						[cn.li.ac.ability.service.player-state :as ps]
						[cn.li.mcmod.util.log :as log]))

(defn handle-set-activated-request
	[{:keys [activated]} player]
	(let [uuid  (uuid/player-uuid player)
				state (common/get-state uuid)
				rd    (:resource-data state)
				before (boolean (:activated rd))
				{:keys [data events]} (res/set-activated rd uuid activated)
				after (boolean (:activated data))]
		(log/info "[V-TRACE][AC][SERVER][REQ-SET-ACTIVATED]"
							{:uuid uuid
							 :requested (boolean activated)
							 :before before
							 :after after
							 :events (count events)})
		(ps/update-resource-data! uuid (constantly data))
		(doseq [e events] (evt/fire-ability-event! e))))