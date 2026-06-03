(ns cn.li.ac.ability.server.handlers.input-handler
	"Input lifecycle request network handlers for ability contexts."
	(:require [cn.li.ac.ability.service.context-manager :as ctx-mgr]
						[cn.li.ac.ability.service.context-dispatcher :as ctx]
						[cn.li.ac.ability.service.context-state :as ctx-rt]
						[cn.li.ac.ability.server.handlers.common :as handlers-common]))

(defn- dispatch-active-input!
	[ctx-id payload player dispatch-fn]
	(when-let [owner (handlers-common/refresh-owned-alive-context! ctx-id player)]
		(if (= ctx-rt/INPUT-ACTIVE (:input-state (ctx/get-context owner ctx-id)))
			(dispatch-fn owner ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)
			nil)))

(defn handle-key-down-skill
	[{:keys [ctx-id] :as payload} player]
	(when-let [owner (handlers-common/refresh-owned-alive-context! ctx-id player)]
		(ctx-rt/handle-key-down! owner ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))

(defn handle-key-tick-skill
	[{:keys [ctx-id] :as payload} player]
	(if (= #{:ctx-id} (set (keys payload)))
		(do
			(handlers-common/refresh-owned-alive-context! ctx-id player)
			nil)
		(dispatch-active-input! ctx-id payload player ctx-rt/handle-key-tick!)
		))

(defn handle-key-up-skill
	[{:keys [ctx-id] :as payload} player]
	(dispatch-active-input! ctx-id payload player ctx-rt/handle-key-up!))

(defn handle-key-abort-skill
	[{:keys [ctx-id] :as payload} player]
	(when-let [owner (handlers-common/refresh-owned-alive-context! ctx-id player)]
		(ctx-rt/handle-key-abort! owner ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))