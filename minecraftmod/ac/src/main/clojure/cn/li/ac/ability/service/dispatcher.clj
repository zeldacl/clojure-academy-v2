(ns cn.li.ac.ability.service.dispatcher
	"Canonical AC context/dispatcher service implementation."
	(:require [cn.li.ac.ability.registry.event :as evt]
						[cn.li.mcmod.util.log :as log]))

(def STATUS-CONSTRUCTED :constructed)
(def STATUS-ALIVE :alive)
(def STATUS-TERMINATED :terminated)

(defn status-valid-transition? [from to]
	(case [from to]
		[:constructed :alive] true
		[:constructed :terminated] true
		[:alive :terminated] true
		false))

(defonce ^:private context-registry (atom {}))
(defonce ^:private route-fns (atom {:to-server nil :to-client nil :to-except-local nil}))
(defonce ^:private client-id-counter (atom 0))
(defonce ^:private server-id-counter (atom 0))

(declare register-context!)

(defn new-context [player-uuid skill-id]
	(let [id (str "cid-" (swap! client-id-counter inc))]
		{:id id :server-id nil :player-uuid player-uuid :skill-id skill-id
		 :status STATUS-CONSTRUCTED :input-state :idle :message-buffer []
		 :listeners {} :last-keepalive-ms nil}))

(defn new-server-context [player-uuid skill-id client-id]
	(let [sid (str "sid-" (swap! server-id-counter inc))]
		{:id client-id :server-id sid :player-uuid player-uuid :skill-id skill-id
		 :status STATUS-ALIVE :input-state :idle :message-buffer []
		 :listeners {} :last-keepalive-ms (System/currentTimeMillis)}))

(defn start-context!
	[player-uuid skill-id]
	(let [ctx (new-context player-uuid skill-id)]
		(register-context! ctx)
		ctx))

(defn start-server-context!
	[player-uuid skill-id client-id]
	(let [ctx (new-server-context player-uuid skill-id client-id)]
		(register-context! ctx)
		ctx))

(defn register-context! [ctx] (swap! context-registry assoc (:id ctx) ctx) ctx)
(defn get-context [ctx-id] (get @context-registry ctx-id))
(defn get-all-contexts [] @context-registry)
(defn update-context! [ctx-id f & args]
	(swap! context-registry
			 (fn [registry]
				 (if (contains? registry ctx-id)
					 (apply update registry ctx-id f args)
					 registry))))
(defn remove-context! [ctx-id] (swap! context-registry dissoc ctx-id))
(defn get-all-contexts-for-player [player-uuid] (filter #(= player-uuid (:player-uuid %)) (vals @context-registry)))

(defn context-owned-by?
	[ctx-id player-uuid]
	(when-let [ctx (get-context ctx-id)]
		(= player-uuid (:player-uuid ctx))))

(defn transition-to-alive! [ctx-id server-id flush-fn]
	(when-let [ctx (get-context ctx-id)]
		(when (status-valid-transition? (:status ctx) STATUS-ALIVE)
			(update-context! ctx-id (fn [c]
															 (let [buffer (:message-buffer c)
																		 alive-ctx (assoc c :status STATUS-ALIVE :server-id server-id :message-buffer [] :last-keepalive-ms (System/currentTimeMillis))]
																 (when (and flush-fn (seq buffer))
																	 (doseq [msg buffer] (flush-fn msg)))
																 alive-ctx)))
			(get-context ctx-id))))

(defn terminate-context! [ctx-id send-terminated-fn]
	(when-let [ctx (get-context ctx-id)]
		(when (not= (:status ctx) STATUS-TERMINATED)
			(update-context! ctx-id assoc :status STATUS-TERMINATED)
			(when send-terminated-fn (send-terminated-fn ctx-id))
			(log/debug "Context terminated:" ctx-id))))

(defn abort-all-contexts-for-player! [player-uuid send-terminated-fn]
	(doseq [ctx (get-all-contexts-for-player player-uuid)]
		(terminate-context! (:id ctx) send-terminated-fn)))

(def ^:private KEEPALIVE-TIMEOUT-MS 1500)
(defn update-keepalive! [ctx-id]
	(update-context! ctx-id assoc :last-keepalive-ms (System/currentTimeMillis)))
(defn check-keepalive-timeout! [send-terminated-fn]
	(let [now (System/currentTimeMillis)]
		(doseq [[ctx-id ctx] @context-registry]
			(when (and (= (:status ctx) STATUS-ALIVE)
								 (:last-keepalive-ms ctx)
								 (> (- now (:last-keepalive-ms ctx)) KEEPALIVE-TIMEOUT-MS))
				(log/debug "Context keepalive timeout:" ctx-id)
				(terminate-context! ctx-id send-terminated-fn)))))

(defn ctx-buffer-or-send! [ctx-id msg send-fn]
	(let [ctx (get-context ctx-id)]
		(when ctx
			(if (= (:status ctx) STATUS-CONSTRUCTED)
				(update-context! ctx-id update :message-buffer conj msg)
				(when send-fn (send-fn msg))))))

(defn ctx-send-to-local! [ctx-id channel msg]
	(when-let [ctx (get-context ctx-id)]
		(doseq [h (get-in ctx [:listeners channel] [])]
			(try (h msg) (catch Exception e (log/warn "Listener threw" (ex-message e)))))))

(defn register-route-fns! [{:keys [to-server to-client to-except-local]}]
	(reset! route-fns {:to-server to-server :to-client to-client :to-except-local to-except-local}))
(defn ctx-send-to-server! [ctx-id channel msg]
	(ctx-buffer-or-send! ctx-id {:channel channel :payload msg}
											 (fn [m] (when-let [f (:to-server @route-fns)] (f ctx-id (:channel m) (:payload m))))))
(defn ctx-send-to-client! [ctx-id channel msg]
	(ctx-buffer-or-send! ctx-id {:channel channel :payload msg}
											 (fn [m] (when-let [f (:to-client @route-fns)] (f ctx-id (:channel m) (:payload m))))))
(defn ctx-send-to-except-local! [ctx-id channel msg]
	(ctx-buffer-or-send! ctx-id {:channel channel :payload msg}
											 (fn [m] (when-let [f (:to-except-local @route-fns)] (f ctx-id (:channel m) (:payload m))))))
(defn ctx-send-to-self! [ctx-id channel msg] (ctx-send-to-local! ctx-id channel msg))
(defn ctx-on! [ctx-id channel handler-fn] (update-context! ctx-id update-in [:listeners channel] (fnil conj []) handler-fn))

(defn active-context? [ctx]
	(not= STATUS-TERMINATED (:status ctx)))

(defn active-contexts
	([]
	 (->> @context-registry
			(filter (fn [[_ctx-id ctx]] (active-context? ctx)))
			(into {})))
	([player-uuid]
	 (->> (get-all-contexts-for-player player-uuid)
			(filter active-context?))))

(defn send-context-message!
	([ctx-id channel payload]
	 (ctx-send-to-local! ctx-id channel payload))
	([ctx-id direction channel payload]
	 (case direction
		 :to-server (ctx-send-to-server! ctx-id channel payload)
		 :to-client (ctx-send-to-client! ctx-id channel payload)
		 :to-except-local (ctx-send-to-except-local! ctx-id channel payload)
		 :to-self (ctx-send-to-self! ctx-id channel payload)
		 ;; keep compatibility: unknown/nil direction falls back to local dispatch
		 (ctx-send-to-local! ctx-id channel payload))))

(defn dispatch-skill-event!
	([event]
	 (evt/fire-ability-event! event))
	([skill-id callback-key event]
	 (evt/fire-ability-event!
		 {:skill-id skill-id
			:callback-key callback-key
			:event event})))