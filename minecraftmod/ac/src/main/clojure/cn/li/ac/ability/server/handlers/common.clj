(ns cn.li.ac.ability.server.handlers.common
	"Shared handler helpers for ability server network handlers."
	(:require 
            [cn.li.ac.ability.service.player-state-core :as ps-core]))

(defn get-state
	[uuid]
	(ps-core/get-or-create-player-state! uuid))

