(ns cn.li.ac.ability.server.handlers.common
	"Shared handler helpers for ability server network handlers."
	(:require [cn.li.mcmod.platform.entity :as entity]
						[cn.li.ac.ability.state.player :as ps]))

(defn uuid-of
	[player]
	(str (entity/player-get-uuid player)))

(defn get-state
	[uuid]
	(ps/get-or-create-player-state! uuid))