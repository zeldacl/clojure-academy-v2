(ns cn.li.ac.ability.server.handlers.common
	"Shared handler helpers for ability server network handlers."
	(:require [cn.li.ac.ability.service.player-state :as ps]))

(defn get-state
	[uuid]
	(ps/get-or-create-player-state! uuid))