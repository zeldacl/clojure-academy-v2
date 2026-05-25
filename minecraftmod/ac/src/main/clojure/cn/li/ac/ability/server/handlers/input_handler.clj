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

(defn- resolve-owned-alive-context
	[ctx-id player]
	(let [ctx-map (when (valid-ctx-id? ctx-id) (ctx/get-context ctx-id))]
		(cond
			(not (valid-ctx-id? ctx-id))
			{:ok? false :reason :payload-invalid}

			(nil? ctx-map)
			{:ok? false :reason :ctx-not-found}

			(nil? (uuid/player-uuid player))
			{:ok? false :reason :player-uuid-missing}

			(not (ctx/context-owned-by? ctx-id (uuid/player-uuid player)))
			{:ok? false :reason :ctx-not-owner}

			(not= (:status ctx-map) ctx/STATUS-ALIVE)
			{:ok? false :reason :ctx-not-alive}

			:else
			{:ok? true :ctx ctx-map})))

(defn- refresh-owned-alive-context!
	[ctx-id player]
	(let [{:keys [ok? reason]} (resolve-owned-alive-context ctx-id player)]
		(if ok?
			(do
				(ctx/update-keepalive! ctx-id)
				true)
			(do
				(record-rejection! reason)
				false))))

(defn- dispatch-active-input!
	[ctx-id payload player dispatch-fn]
	(when (refresh-owned-alive-context! ctx-id player)
		(if (= ctx-rt/INPUT-ACTIVE (:input-state (ctx/get-context ctx-id)))
			(dispatch-fn ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)
			(record-rejection! :message-out-of-order))))

(defn handle-key-down-skill
	[{:keys [ctx-id] :as payload} player]
	(when (refresh-owned-alive-context! ctx-id player)
		(ctx-rt/handle-key-down! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))

(defn handle-key-tick-skill
	[{:keys [ctx-id] :as payload} player]
	(dispatch-active-input! ctx-id payload player ctx-rt/handle-key-tick!))

(defn handle-key-up-skill
	[{:keys [ctx-id] :as payload} player]
	(dispatch-active-input! ctx-id payload player ctx-rt/handle-key-up!))

(defn handle-key-abort-skill
	[{:keys [ctx-id] :as payload} player]
	(when (refresh-owned-alive-context! ctx-id player)
		(ctx-rt/handle-key-abort! ctx-id (assoc payload :player player) ctx-mgr/send-terminated-context!)))