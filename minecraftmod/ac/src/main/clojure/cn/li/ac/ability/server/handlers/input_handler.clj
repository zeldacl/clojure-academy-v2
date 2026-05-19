(ns cn.li.ac.ability.server.handlers.input-handler
	"Input lifecycle request network handlers for ability contexts."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
						[cn.li.ac.ability.server.service.context-runtime :as ctx-rt]
						[cn.li.ac.ability.service.dispatcher :as ctx]))

(defn- owned-context?
	[ctx-id player]
	(when-let [player-uuid (uuid/player-uuid player)]
		(ctx/context-owned-by? ctx-id player-uuid)))

(defn- ensure-owned-context!
	[ctx-id payload player]
	(when-let [player-uuid (uuid/player-uuid player)]
		(let [existing (ctx/get-context ctx-id)]
			(cond
				(some? existing)
				(= player-uuid (:player-uuid existing))

				(:skill-id payload)
				(do
					(ctx-mgr/establish-context! player-uuid ctx-id (:skill-id payload))
					(ctx/context-owned-by? ctx-id player-uuid))

				:else false))))

(defn- refresh-owned-context!
	[ctx-id player]
	(when (owned-context? ctx-id player)
		(ctx/update-keepalive! ctx-id)
		true))

(defn handle-key-down-skill
	[{:keys [ctx-id] :as payload} player]
	(when (ensure-owned-context! ctx-id payload player)
		(ctx/update-keepalive! ctx-id)
		(ctx-rt/handle-key-down! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))

(defn handle-key-tick-skill
	[{:keys [ctx-id] :as payload} player]
	(when (refresh-owned-context! ctx-id player)
		(ctx-rt/handle-key-tick! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))

(defn handle-key-up-skill
	[{:keys [ctx-id] :as payload} player]
	(when (refresh-owned-context! ctx-id player)
		(ctx-rt/handle-key-up! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))

(defn handle-key-abort-skill
	[{:keys [ctx-id] :as payload} player]
	(when (refresh-owned-context! ctx-id player)
		(ctx-rt/handle-key-abort! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))