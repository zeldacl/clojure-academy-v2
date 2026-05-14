(ns cn.li.ac.ability.server.handlers.preset-handler
	"Preset request network handlers."
	(:require [cn.li.ac.ability.server.handlers.common :as common]
						[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.model.preset :as preset-data]
						[cn.li.ac.ability.registry.event :as evt]
						[cn.li.ac.ability.service.player-state :as ps]))

(defn handle-set-preset-request
	[{:keys [preset-idx key-idx cat-id ctrl-id]} player]
	(let [uuid (uuid/player-uuid-str player)]
		(ps/update-preset-data! uuid
														preset-data/set-slot
														preset-idx key-idx
														(when (and cat-id ctrl-id) [cat-id ctrl-id]))))

(defn handle-switch-preset-request
	[{:keys [preset-idx]} player]
	(let [uuid (uuid/player-uuid-str player)]
		(ps/update-preset-data! uuid preset-data/set-active-preset preset-idx)
		(evt/fire-ability-event! {:event/type evt/EVT-PRESET-SWITCH
															:player-id uuid
															:preset    preset-idx})))