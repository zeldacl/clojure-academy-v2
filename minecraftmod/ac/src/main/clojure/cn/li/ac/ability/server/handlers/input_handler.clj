(ns cn.li.ac.ability.server.handlers.input-handler
	"Input lifecycle request network handlers for ability contexts."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
						[cn.li.ac.ability.server.service.context-runtime :as ctx-rt]
						[cn.li.ac.ability.service.dispatcher :as ctx]))

(defn handle-key-down-skill
	[{:keys [ctx-id] :as payload} player]
	(let [payload* (assoc payload :player player)
				ctx0 (ctx/get-context ctx-id)
				_ (when (nil? ctx0)
						(when-let [skill-id (:skill-id payload*)]
							(ctx-mgr/establish-context! (uuid/player-uuid player) ctx-id skill-id)))]
		(ctx-rt/handle-key-down! ctx-id payload* ctx-mgr/send-terminated-context!)))

(defn handle-key-tick-skill
	[{:keys [ctx-id] :as payload} player]
	(ctx-rt/handle-key-tick! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))

(defn handle-key-up-skill
	[{:keys [ctx-id] :as payload} player]
	(ctx-rt/handle-key-up! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))

(defn handle-key-abort-skill
	[{:keys [ctx-id] :as payload} player]
	(ctx-rt/handle-key-abort! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))