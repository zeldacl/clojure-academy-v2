(ns cn.li.ac.ability.server.handlers.context-handler
	"Context lifecycle request network handlers."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
						[cn.li.ac.ability.service.dispatcher :as ctx]))

(defn handle-begin-link-context
	[{:keys [ctx-id skill-id]} player]
	(when-let [player-uuid (uuid/player-uuid player)]
		(let [existing (ctx/get-context ctx-id)]
			(when (or (nil? existing)
						  (= player-uuid (:player-uuid existing)))
				(ctx-mgr/establish-context! player-uuid ctx-id skill-id)))))

(defn- owned-context?
	[ctx-id player]
	(when-let [player-uuid (uuid/player-uuid player)]
		(ctx/context-owned-by? ctx-id player-uuid)))

(defn handle-keepalive-context
	[{:keys [ctx-id]} player]
	(when (owned-context? ctx-id player)
		(ctx/update-keepalive! ctx-id)))

(defn handle-terminate-context
	[{:keys [ctx-id]} player]
	(when (owned-context? ctx-id player)
		(ctx/terminate-context! ctx-id ctx-mgr/send-terminated-context!)))

(defn handle-channel-context
	[{:keys [ctx-id channel payload]} player]
	(when (owned-context? ctx-id player)
		(ctx/update-keepalive! ctx-id)
		(ctx/ctx-send-to-local! ctx-id channel payload)))