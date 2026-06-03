(ns cn.li.ac.ability.server.handlers.context-handler
	"Context lifecycle request network handlers."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[cn.li.ac.ability.service.context-manager :as ctx-mgr]
						[cn.li.ac.ability.service.context-dispatcher :as ctx]
						[cn.li.ac.ability.server.handlers.common :as handlers-common]))

(defn handle-begin-link-context
	[{:keys [ctx-id skill-id]} player]
	(cond
		(or (not (handlers-common/valid-ctx-id? ctx-id)) (not (keyword? skill-id)))
		nil

		(nil? (uuid/player-uuid player))
		nil

		:else
		(let [player-uuid (uuid/player-uuid player)
				owner (handlers-common/server-context-owner player-uuid)]
			(let [existing (ctx/get-context owner ctx-id)]
				(if (or (nil? existing)
						(= player-uuid (:player-uuid existing)))
					(ctx-mgr/establish-context! player-uuid ctx-id skill-id)
					nil)))))

(defn handle-keepalive-context
	[{:keys [ctx-id]} player]
	(let [{:keys [ok? owner]} (handlers-common/resolve-owned-alive-context ctx-id player)]
		(if ok?
			(do
				(handlers-common/sync-keepalive-command! owner ctx-id))
			nil)))

(defn handle-terminate-context
	[{:keys [ctx-id]} player]
	(let [{:keys [ok? owner]} (handlers-common/resolve-owned-context ctx-id player)]
		(if ok?
			(ctx/terminate-context! owner ctx-id ctx-mgr/send-terminated-context!)
			nil)))

(defn handle-channel-context
	[{:keys [ctx-id channel payload]} player]
	(let [{:keys [ok? owner]} (if (keyword? channel)
													(handlers-common/resolve-owned-alive-context ctx-id player)
													{:ok? false :reason :payload-invalid})]
		(if ok?
			(do
				(handlers-common/sync-keepalive-command! owner ctx-id)
				(ctx/ctx-send-to-local! owner ctx-id channel payload))
			nil)))