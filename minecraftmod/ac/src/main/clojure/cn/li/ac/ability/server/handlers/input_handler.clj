(ns cn.li.ac.ability.server.handlers.input-handler
	"Input lifecycle request network handlers for ability contexts."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[clojure.string :as str]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
						[cn.li.ac.ability.server.service.context-runtime :as ctx-rt]
						[cn.li.ac.ability.service.dispatcher :as ctx]))

(defonce ^:private rejection-counters (atom {}))

(defn reset-rejection-counters!
	[]
	(reset! rejection-counters {})
	nil)

(defn rejection-counters-snapshot
	[]
	@rejection-counters)

(defn- record-rejection!
	[reason]
	(swap! rejection-counters update reason (fnil inc 0))
	nil)

(defn- valid-ctx-id?
	[ctx-id]
	(and (string? ctx-id) (not (str/blank? ctx-id))))

(defn- server-context-owner
	[player-uuid]
	{:logical-side :server
	 :session-id player-uuid})

(defn- resolve-owned-alive-context
	[ctx-id player]
	(let [player-uuid (uuid/player-uuid player)
			owner (when player-uuid (server-context-owner player-uuid))
			ctx-map (when (and owner (valid-ctx-id? ctx-id))
								(binding [ctx/*context-owner* owner]
									(ctx/get-context ctx-id)))]
		(cond
			(not (valid-ctx-id? ctx-id))
			{:ok? false :reason :payload-invalid}

			(nil? player-uuid)
			{:ok? false :reason :player-uuid-missing}

			(nil? ctx-map)
			{:ok? false :reason :ctx-not-found}

			(not= player-uuid (:player-uuid ctx-map))
			{:ok? false :reason :ctx-not-owner}

			(not= (:status ctx-map) ctx/STATUS-ALIVE)
			{:ok? false :reason :ctx-not-alive}

			:else
			{:ok? true :ctx ctx-map :owner owner})))

(defn- refresh-owned-alive-context!
	[ctx-id player]
	(let [{:keys [ok? reason owner]} (resolve-owned-alive-context ctx-id player)]
		(if ok?
			(do
				(binding [ctx/*context-owner* owner]
					(ctx/update-keepalive! ctx-id))
				owner)
			(do
				(record-rejection! reason)
				nil))))

(defn- dispatch-active-input!
	[ctx-id payload player dispatch-fn]
	(when-let [owner (refresh-owned-alive-context! ctx-id player)]
		(binding [ctx/*context-owner* owner]
		(if (= ctx-rt/INPUT-ACTIVE (:input-state (ctx/get-context ctx-id)))
			(dispatch-fn ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)
			(record-rejection! :message-out-of-order)))))

(defn handle-key-down-skill
	[{:keys [ctx-id] :as payload} player]
	(when-let [owner (refresh-owned-alive-context! ctx-id player)]
		(binding [ctx/*context-owner* owner]
			(ctx-rt/handle-key-down! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))))

(defn handle-key-tick-skill
	[{:keys [ctx-id] :as payload} player]
	(dispatch-active-input! ctx-id payload player ctx-rt/handle-key-tick!))

(defn handle-key-up-skill
	[{:keys [ctx-id] :as payload} player]
	(dispatch-active-input! ctx-id payload player ctx-rt/handle-key-up!))

(defn handle-key-abort-skill
	[{:keys [ctx-id] :as payload} player]
	(when-let [owner (refresh-owned-alive-context! ctx-id player)]
		(binding [ctx/*context-owner* owner]
			(ctx-rt/handle-key-abort! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!))))