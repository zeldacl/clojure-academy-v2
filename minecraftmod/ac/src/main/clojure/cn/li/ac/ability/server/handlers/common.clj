(ns cn.li.ac.ability.server.handlers.common
	"Shared handler helpers for ability server network handlers."
	(:require 
            [cn.li.ac.ability.service.runtime-store :as store]
						[cn.li.mcmod.hooks.core :as runtime-hooks]))

(defonce ^:private server-session-id-resolver*
	(atom (fn [] (runtime-hooks/player-state-server-session-id))))

(defonce ^:private owner-resolver*
	(atom (fn [] (runtime-hooks/current-player-state-owner))))

(defn install-session-runtime!
	"Install runtime callbacks used by server handler helper defaults.

	Keys:
	- :server-session-id-resolver (fn [] -> string|nil)
	- :owner-resolver (fn [] -> map|nil)"
	[{:keys [server-session-id-resolver owner-resolver]}]
	(when server-session-id-resolver
		(reset! server-session-id-resolver* server-session-id-resolver))
	(when owner-resolver
		(reset! owner-resolver* owner-resolver))
	nil)

(defn current-server-session-id
	[]
	((or @server-session-id-resolver*
		 (fn [] (runtime-hooks/player-state-server-session-id)))))

(defn require-server-session-id
	[reason]
	(or (current-server-session-id)
			(runtime-hooks/require-player-state-server-session-id reason)))

(defn server-context-owner
	[player-uuid]
	(let [server-session-id (require-server-session-id "Server context owner")]
		{:logical-side :server
		 :server-session-id server-session-id
		 :session-id [server-session-id player-uuid]
		 :player-uuid player-uuid}))

(defn- current-owner
	[]
	((or @owner-resolver*
		 (fn [] (runtime-hooks/current-player-state-owner)))))

(defn get-state
	[uuid]
	(let [session-id (current-server-session-id)]
		(when-not session-id
			(throw (ex-info "Server handler state read requires :server-session-id/:session-id"
							{:uuid uuid
							 :player-state-owner (current-owner)})))
		(store/get-or-create-player-state! session-id uuid)))

