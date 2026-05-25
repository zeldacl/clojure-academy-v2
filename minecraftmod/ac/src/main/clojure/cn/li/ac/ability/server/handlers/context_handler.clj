(ns cn.li.ac.ability.server.handlers.context-handler
	"Context lifecycle request network handlers."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[clojure.string :as str]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
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

(defn handle-begin-link-context
	[{:keys [ctx-id skill-id]} player]
	(cond
		(or (not (valid-ctx-id? ctx-id)) (not (keyword? skill-id)))
		(record-rejection! :payload-invalid)

		(nil? (uuid/player-uuid player))
		(record-rejection! :player-uuid-missing)

		:else
		(let [player-uuid (uuid/player-uuid player)
				existing (ctx/get-context ctx-id)]
			(if (or (nil? existing)
						(= player-uuid (:player-uuid existing)))
				(ctx-mgr/establish-context! player-uuid ctx-id skill-id)
				(record-rejection! :ctx-not-owner)))))

(defn- resolve-owned-context
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

			:else
			{:ok? true :ctx ctx-map})))

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

(defn handle-keepalive-context
	[{:keys [ctx-id]} player]
	(let [{:keys [ok? reason]} (resolve-owned-alive-context ctx-id player)]
		(if ok?
			(ctx/update-keepalive! ctx-id)
			(record-rejection! reason))))

(defn handle-terminate-context
	[{:keys [ctx-id]} player]
	(let [{:keys [ok? reason]} (resolve-owned-context ctx-id player)]
		(if ok?
			(ctx/terminate-context! ctx-id ctx-mgr/send-terminated-context!)
			(record-rejection! reason))))

(defn handle-channel-context
	[{:keys [ctx-id channel payload]} player]
	(let [{:keys [ok? reason]} (if (keyword? channel)
													(resolve-owned-alive-context ctx-id player)
													{:ok? false :reason :payload-invalid})]
		(if ok?
			(do
				(ctx/update-keepalive! ctx-id)
				(ctx/ctx-send-to-local! ctx-id channel payload))
			(record-rejection! reason))))