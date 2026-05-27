(ns cn.li.ac.ability.server.handlers.context-handler
	"Context lifecycle request network handlers."
	(:require [cn.li.ac.ability.util.uuid :as uuid]
						[clojure.string :as str]
						[cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
						[cn.li.ac.ability.service.dispatcher :as ctx]
						[cn.li.mcmod.hooks.core :as runtime-hooks]))

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
	(let [owner runtime-hooks/*player-state-owner*
			server-session-id (:server-session-id owner)]
		(when-not server-session-id
			(throw (ex-info "Server context owner requires bound :server-session-id"
									{:player-uuid player-uuid
									 :player-state-owner owner})))
		{:logical-side :server
		 :server-session-id server-session-id
		 :session-id [server-session-id player-uuid]
		 :player-uuid player-uuid}))

(defn handle-begin-link-context
	[{:keys [ctx-id skill-id]} player]
	(cond
		(or (not (valid-ctx-id? ctx-id)) (not (keyword? skill-id)))
		(record-rejection! :payload-invalid)

		(nil? (uuid/player-uuid player))
		(record-rejection! :player-uuid-missing)

		:else
		(let [player-uuid (uuid/player-uuid player)
				owner (server-context-owner player-uuid)]
			(binding [ctx/*context-owner* owner]
				(let [existing (ctx/get-context ctx-id)]
					(if (or (nil? existing)
								(= player-uuid (:player-uuid existing)))
						(ctx-mgr/establish-context! player-uuid ctx-id skill-id)
						(record-rejection! :ctx-not-owner)))))))

(defn- resolve-owned-context
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

			:else
			{:ok? true :ctx ctx-map :owner owner})))

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

(defn handle-keepalive-context
	[{:keys [ctx-id]} player]
	(let [{:keys [ok? reason owner]} (resolve-owned-alive-context ctx-id player)]
		(if ok?
			(binding [ctx/*context-owner* owner]
				(ctx/update-keepalive! ctx-id))
			(record-rejection! reason))))

(defn handle-terminate-context
	[{:keys [ctx-id]} player]
	(let [{:keys [ok? reason owner]} (resolve-owned-context ctx-id player)]
		(if ok?
			(binding [ctx/*context-owner* owner]
				(ctx/terminate-context! ctx-id ctx-mgr/send-terminated-context!))
			(record-rejection! reason))))

(defn handle-channel-context
	[{:keys [ctx-id channel payload]} player]
	(let [{:keys [ok? reason owner]} (if (keyword? channel)
													(resolve-owned-alive-context ctx-id player)
													{:ok? false :reason :payload-invalid})]
		(if ok?
			(binding [ctx/*context-owner* owner]
				(ctx/update-keepalive! ctx-id)
				(ctx/ctx-send-to-local! ctx-id channel payload))
			(record-rejection! reason))))