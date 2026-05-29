(ns cn.li.ac.ability.server.handlers.level-handler
	"Level-up request network handler."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.service.command-runtime :as command-rt]))

(defn handle-level-up-request
	[_payload player]
	(let [uuid  (uuid/player-uuid player)
				_result (command-rt/run-command! uuid {:command :level-up})]
		nil))