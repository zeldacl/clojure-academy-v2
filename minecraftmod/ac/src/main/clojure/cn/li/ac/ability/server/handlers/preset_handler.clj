(ns cn.li.ac.ability.server.handlers.preset-handler
	"Preset request network handlers."
	(:require [cn.li.ac.ability.server.handlers.common :as common]
						[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.model.ability :as ability-data]
						[cn.li.ac.ability.model.preset :as preset-data]
						[cn.li.ac.ability.registry.event :as evt]
						[cn.li.ac.ability.registry.skill-query :as skill-query]
						[cn.li.ac.ability.service.player-state :as ps]))

(defn- learned-controllable-slot
	[player-uuid cat-id ctrl-id]
	(when-let [skill-id (skill-query/get-skill-by-controllable cat-id ctrl-id)]
		(when (ability-data/is-learned? (:ability-data (ps/get-player-state player-uuid)) skill-id)
			[cat-id ctrl-id])))

(defn handle-set-preset-request
	[{:keys [preset-idx key-idx cat-id ctrl-id]} player]
	(let [uuid (uuid/player-uuid player)]
		(when uuid
			(cond
				(and (nil? cat-id) (nil? ctrl-id))
				(do
					(ps/update-preset-data! uuid preset-data/set-slot preset-idx key-idx nil)
					(evt/fire-ability-event!
						(evt/make-preset-update-event uuid preset-idx key-idx nil)))

				(and cat-id ctrl-id)
				(when-let [slot (learned-controllable-slot uuid cat-id ctrl-id)]
					(ps/update-preset-data! uuid preset-data/set-slot preset-idx key-idx slot)
					(evt/fire-ability-event!
						(evt/make-preset-update-event uuid preset-idx key-idx slot)))))))

(defn handle-switch-preset-request
	[{:keys [preset-idx]} player]
	(let [uuid (uuid/player-uuid player)]
		(when uuid
			(let [old-preset (get-in (common/get-state uuid) [:preset-data :active-preset] 0)]
				(ps/update-preset-data! uuid preset-data/set-active-preset preset-idx)
				(evt/fire-ability-event!
					(evt/make-preset-switch-event uuid old-preset preset-idx))))))