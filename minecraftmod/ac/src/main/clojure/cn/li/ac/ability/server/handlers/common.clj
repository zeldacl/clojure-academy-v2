(ns cn.li.ac.ability.server.handlers.common
	"Shared handler helpers for ability server network handlers."
	(:require 
            [cn.li.ac.ability.service.runtime-store :as store]
						[cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn current-server-session-id
	[]
	(runtime-hooks/player-state-server-session-id))

(defn get-state
	[uuid]
	(let [session-id (current-server-session-id)]
		(when-not session-id
			(throw (ex-info "Server handler state read requires :server-session-id/:session-id"
							{:uuid uuid
							 :player-state-owner (runtime-hooks/current-player-state-owner)})))
		(store/get-or-create-player-state! session-id uuid)))

