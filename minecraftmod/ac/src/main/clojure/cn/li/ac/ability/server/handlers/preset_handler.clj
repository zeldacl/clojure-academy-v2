(ns cn.li.ac.ability.server.handlers.preset-handler
	"Preset request network handlers."
	(:require 
            [cn.li.ac.ability.service.player-state-core :as ps-core]
[cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.model.ability :as ability-data]
						[cn.li.ac.ability.service.command-runtime :as command-rt]
						[cn.li.ac.ability.registry.skill-query :as skill-query]))

(defn- learned-controllable-slot
	[player-uuid cat-id ctrl-id]
	(when-let [skill-id (skill-query/get-skill-by-controllable cat-id ctrl-id)]
		(when (ability-data/is-learned? (:ability-data (ps-core/get-player-state player-uuid)) skill-id)
			[cat-id ctrl-id])))

(defn handle-set-preset-request
	[{:keys [preset-idx key-idx cat-id ctrl-id]} player]
	(let [uuid (uuid/player-uuid player)]
		(when uuid
			(cond
				(and (nil? cat-id) (nil? ctrl-id))
				(command-rt/run-command! uuid {:command :set-preset-slot
															 :preset-idx preset-idx
															 :key-idx key-idx
															 :controllable nil})

				(and cat-id ctrl-id)
				(when-let [slot (learned-controllable-slot uuid cat-id ctrl-id)]
					(command-rt/run-command! uuid {:command :set-preset-slot
															 :preset-idx preset-idx
															 :key-idx key-idx
															 :controllable slot}))))))

(defn handle-switch-preset-request
	[{:keys [preset-idx]} player]
	(let [uuid (uuid/player-uuid player)]
		(when uuid
			(command-rt/run-command! uuid {:command :switch-preset
														 :preset-idx preset-idx}))))
