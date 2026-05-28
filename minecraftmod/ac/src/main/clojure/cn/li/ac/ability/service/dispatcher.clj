(ns cn.li.ac.ability.service.dispatcher
	"Canonical AC context/dispatcher service implementation."
	(:require [cn.li.ac.ability.registry.event :as evt]
						[clojure.string :as str]
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

(def ^:private STATIC-ROUTE-OWNER [::static-routes ::process])

(def ^:dynamic *context-owner*
	"Explicit runtime owner for resolving otherwise opaque ctx-id values within
	a request/callback stack. Server handlers bind this to a player-owned session
	so identical client-generated context ids from different players do not
	collide in multiplayer."
	nil)

(def ^:private default-dispatcher-state
	{:context-registry {}
	 :route-fns {}
	 :client-id-counter {}
	 :server-id-counter {}
	 :lifecycle-counters {}})

(defn create-dispatcher-runtime
	[]
	{::runtime ::dispatcher-runtime
	 :dispatcher-state* (atom default-dispatcher-state)})

(def ^:dynamic *dispatcher-runtime* nil)

(defonce ^:private installed-dispatcher-runtime
	(create-dispatcher-runtime))

(defn- dispatcher-runtime?
	[runtime]
	(and (map? runtime)
			 (= ::dispatcher-runtime (::runtime runtime))
			 (some? (:dispatcher-state* runtime))))

(defn call-with-dispatcher-runtime
	[runtime f]
	(when-not (dispatcher-runtime? runtime)
		(throw (ex-info "Expected dispatcher runtime"
							{:runtime runtime})))
	(binding [*dispatcher-runtime* runtime]
		(f)))

(defmacro with-dispatcher-runtime
	[runtime & body]
	`(call-with-dispatcher-runtime ~runtime (fn [] ~@body)))

(defn- current-dispatcher-runtime
	[]
	(or *dispatcher-runtime*
			installed-dispatcher-runtime))

(defn- dispatcher-state-atom
	[]
	(:dispatcher-state* (current-dispatcher-runtime)))

(defn- dispatcher-state-snapshot
	[]
	@(dispatcher-state-atom))

(defn- update-dispatcher-state!
	[f & args]
	(apply swap! (dispatcher-state-atom) f args))

(defn- context-registry-snapshot
	[]
	(:context-registry (dispatcher-state-snapshot)))

(defn- route-fns-snapshot
	[]
	(:route-fns (dispatcher-state-snapshot)))

(defn- lifecycle-counters-map
	[]
	(:lifecycle-counters (dispatcher-state-snapshot)))

(def ^:private DEFAULT-TERMINATED-CONTEXT-GRACE-MS 1000)
(def ^:private DEFAULT-KEEPALIVE-TIMEOUT-MS 1500)

(defn- positive-long-prop
	[prop-key default-value]
	(let [raw (System/getProperty prop-key)]
		(if (and raw (not (str/blank? raw)))
			(try
				(let [parsed (Long/parseLong raw)]
					(if (pos? parsed) parsed default-value))
				(catch Exception _ default-value))
			default-value)))

(defn keepalive-timeout-ms
	"Server-side keepalive timeout threshold in milliseconds.
	Configurable through -Dac.ctx.keepalive-timeout-ms (default 1500)."
	[]
	(positive-long-prop "ac.ctx.keepalive-timeout-ms" DEFAULT-KEEPALIVE-TIMEOUT-MS))

(defn- require-session-id
	[owner session-id]
	(if (some? session-id)
		session-id
		(throw (ex-info "Context owner requires :session-id"
								{:owner owner}))))

(defn- normalize-logical-side
	[logical-side]
	(case logical-side
		(:client "client" :logical-side/client) :client
		(:server "server" :logical-side/server) :server
		(:any "any") :any
		logical-side))

(defn- context-logical-side
	[ctx]
	(normalize-logical-side (or (:logical-side ctx)
																	(when (:server-id ctx) :server)
																	:client)))

(defn- context-session-id
	[ctx]
	(require-session-id ctx (:session-id ctx)))

(defn- context-registry-key
	[ctx]
	[(context-logical-side ctx) (context-session-id ctx) (:id ctx)])

(defn- route-owner-key
	[owner]
	(let [logical-side (or (:logical-side owner) (:side owner))
				session-id (:session-id owner)]
		(if (and (nil? logical-side) (nil? session-id))
			STATIC-ROUTE-OWNER
			[(normalize-logical-side logical-side)
			 (require-session-id owner session-id)])))

(defn- context-owner-key
	[owner logical-side]
	[(normalize-logical-side logical-side)
	 (require-session-id owner (:session-id owner))])

(defn- context-with-owner
	[ctx]
	(assoc ctx
			 :logical-side (context-logical-side ctx)
			 :session-id (context-session-id ctx)))

(defn- next-context-id!
	[counter-key owner prefix]
	(let [counter* (volatile! nil)]
		(update-dispatcher-state!
			(fn [state]
				(let [next-counter (inc (get-in state [counter-key owner] 0))]
					(vreset! counter* next-counter)
					(assoc-in state [counter-key owner] next-counter))))
		(str prefix "-" @counter*)))

(defn- require-context-owner
	[ctx-id preferred-side]
	(when-not *context-owner*
		(throw (ex-info "Opaque ctx-id resolution requires *context-owner* or an explicit owner"
							{:ctx-id ctx-id
							 :preferred-side preferred-side}))))

(defn- preferred-context-entry
	([ctx-id]
	 (preferred-context-entry ctx-id nil))
	([ctx-id preferred-side]
	 (if (vector? ctx-id)
		 (when-let [ctx (get (context-registry-snapshot) ctx-id)]
			 [ctx-id ctx])
		 (do
			 (require-context-owner ctx-id preferred-side)
			 (let [owner (cond-> *context-owner*
								 preferred-side (assoc :logical-side preferred-side))
					 [side session] (route-owner-key owner)
					 key [side session ctx-id]]
				 (when-let [ctx (get (context-registry-snapshot) key)]
					 [key ctx]))))))

(defn- route-fns-for-context
	[ctx]
	(let [side-key [(context-logical-side ctx) (context-session-id ctx)]
				 session-any-key [:any (context-session-id ctx)]]
		(or (get (route-fns-snapshot) side-key)
				(get (route-fns-snapshot) session-any-key)
				(get (route-fns-snapshot) STATIC-ROUTE-OWNER)
				{:to-server nil :to-client nil :to-except-local nil})))

(defn reset-lifecycle-counters!
	[]
	(update-dispatcher-state! assoc :lifecycle-counters {})
	nil)

(defn lifecycle-counters-snapshot
	([]
	 (apply merge-with + (vals (lifecycle-counters-map))))
	([owner]
	 (get (lifecycle-counters-map) (route-owner-key owner) {})))

(defn- record-lifecycle-event!
	([reason]
	 (record-lifecycle-event! reason {}))
	([reason owner]
	 (update-dispatcher-state! update-in [:lifecycle-counters (route-owner-key owner) reason] (fnil inc 0))
	 nil))

(defn terminated-context-grace-ms
	"Grace window before terminated contexts are purged from registry.
	Configurable through -Dac.ctx.terminated-grace-ms (default 1000)."
	[]
	(positive-long-prop "ac.ctx.terminated-grace-ms" DEFAULT-TERMINATED-CONTEXT-GRACE-MS))

(declare register-context!)

(defn new-context
	([player-uuid skill-id]
	 (new-context player-uuid skill-id *context-owner*))
	([player-uuid skill-id owner]
	 (let [owner-key (context-owner-key owner :client)
				 id (next-context-id! :client-id-counter owner-key "cid")]
		 {:id id :server-id nil :player-uuid player-uuid :skill-id skill-id
			:logical-side :client :session-id (second owner-key)
			:status STATUS-CONSTRUCTED :input-state :idle :message-buffer []
			:listeners {} :last-keepalive-ms nil :terminated-at-ms nil})))

(defn new-server-context
	([player-uuid skill-id client-id]
	 (new-server-context player-uuid skill-id client-id *context-owner*))
	([player-uuid skill-id client-id owner]
	 (let [owner-key (context-owner-key owner :server)
				 sid (next-context-id! :server-id-counter owner-key "sid")]
		 {:id client-id :server-id sid :player-uuid player-uuid :skill-id skill-id
			:logical-side :server :session-id (second owner-key)
			:status STATUS-ALIVE :input-state :idle :message-buffer []
			:listeners {} :last-keepalive-ms (System/currentTimeMillis) :terminated-at-ms nil})))

(defn start-context!
	[player-uuid skill-id]
	(let [ctx (new-context player-uuid skill-id *context-owner*)]
		(register-context! ctx)
		ctx))

(defn start-server-context!
	[player-uuid skill-id client-id]
	(let [ctx (new-server-context player-uuid skill-id client-id *context-owner*)]
		(register-context! ctx)
		ctx))

(defn register-context! [ctx]
	(let [ctx* (context-with-owner ctx)]
		(update-dispatcher-state! assoc-in [:context-registry (context-registry-key ctx*)] ctx*)
		ctx*))

(defn get-context
	([ctx-id]
	 (second (preferred-context-entry ctx-id)))
	([owner ctx-id]
	 (let [[side session] (route-owner-key owner)]
		 (get (context-registry-snapshot) [side session ctx-id]))))

(defn get-all-contexts [] (context-registry-snapshot))
(defn update-context! [ctx-id f & args]
	(when-let [[key _ctx] (preferred-context-entry ctx-id)]
		(update-dispatcher-state!
			(fn [state]
				(if (contains? (:context-registry state) key)
					(apply update-in state [:context-registry key] f args)
					state)))))
(defn remove-context! [ctx-id]
	(if (vector? ctx-id)
		(update-dispatcher-state! update :context-registry dissoc ctx-id)
		(when-let [[key _ctx] (preferred-context-entry ctx-id)]
			(update-dispatcher-state! update :context-registry dissoc key))))

(defn- owner-matches-context?
	[owner ctx]
	(let [[logical-side session-id] (route-owner-key owner)]
		(and (= logical-side (context-logical-side ctx))
			 (= session-id (context-session-id ctx)))))

(defn get-all-contexts-for-player
	([player-uuid]
	 (filter #(= player-uuid (:player-uuid %)) (vals (context-registry-snapshot))))
	([owner player-uuid]
	 (filter #(and (= player-uuid (:player-uuid %))
					 (owner-matches-context? owner %))
			 (vals (context-registry-snapshot)))))

(defn snapshot-context-registry []
	(context-registry-snapshot))

(defn clear-owner-contexts!
	"Clear all registered contexts and counters for one logical owner."
	[owner]
	(let [owner-key (route-owner-key owner)]
		(update-dispatcher-state!
			(fn [state]
				(let [filtered-contexts (into {}
											 (remove (fn [[_ctx-id ctx-map]]
														 (= owner-key [(context-logical-side ctx-map)
																				 (context-session-id ctx-map)])))
											 (:context-registry state))]
					(-> state
							(assoc :context-registry filtered-contexts)
							(update :client-id-counter dissoc owner-key)
							(update :server-id-counter dissoc owner-key)
							(update :lifecycle-counters dissoc owner-key)))))
	nil))

(defn clear-session-contexts!
	"Clear all registered contexts and counters for one session id."
	[session-id]
	(update-dispatcher-state!
		(fn [state]
			(-> state
					(update :context-registry
							(fn [registry]
								(into {}
										(remove (fn [[_ctx-id ctx-map]]
												 (= session-id (context-session-id ctx-map))))
										registry)))
					(update :client-id-counter
							(fn [counters]
								(into {}
										(remove (fn [[[ _side owner-session] _counter]]
												 (= session-id owner-session)))
										counters)))
					(update :server-id-counter
							(fn [counters]
								(into {}
										(remove (fn [[[ _side owner-session] _counter]]
												 (= session-id owner-session)))
										counters)))
					(update :lifecycle-counters
							(fn [counters]
								(into {}
										(remove (fn [[[ _side owner-session] _counter]]
												 (= session-id owner-session)))
										counters))))))
	nil)

(defn reset-contexts-for-test!
	([]
	 (reset-contexts-for-test! {}))
	([contexts]
	 (update-dispatcher-state!
			(fn [state]
				(-> state
						(assoc :context-registry contexts)
						(assoc :client-id-counter {})
						(assoc :server-id-counter {}))))
	 nil))

(defn reset-route-fns-for-test!
	[]
	(update-dispatcher-state! assoc :route-fns {})
	nil)

(defn context-owned-by?
	[ctx-id player-uuid]
	(when-let [ctx (get-context ctx-id)]
		(= player-uuid (:player-uuid ctx))))

(defn transition-to-alive! [ctx-id server-id flush-fn]
	(when-let [[_key ctx] (preferred-context-entry ctx-id :client)]
		(when (status-valid-transition? (:status ctx) STATUS-ALIVE)
			(update-context! ctx-id (fn [c]
															 (let [buffer (:message-buffer c)
																					 alive-ctx (assoc c :status STATUS-ALIVE :server-id server-id :message-buffer [] :last-keepalive-ms (System/currentTimeMillis) :terminated-at-ms nil)]
																 (when (and flush-fn (seq buffer))
																	 (doseq [msg buffer] (flush-fn msg)))
																 alive-ctx)))
			(get-context ctx-id))))

(defn terminate-context! [ctx-id send-terminated-fn]
	(when-let [ctx (get-context ctx-id)]
		(when (not= (:status ctx) STATUS-TERMINATED)
			(update-context! ctx-id assoc :status STATUS-TERMINATED :terminated-at-ms (System/currentTimeMillis))
			(when send-terminated-fn
				(binding [*context-owner* {:logical-side (context-logical-side ctx)
												 :session-id (context-session-id ctx)}]
					(send-terminated-fn (:id ctx))))
			(log/debug "Context terminated:" (:id ctx)))))

(defn abort-all-contexts-for-player!
	([player-uuid send-terminated-fn]
	 (doseq [ctx (get-all-contexts-for-player player-uuid)]
		 (terminate-context! (:id ctx) send-terminated-fn)))
	([owner player-uuid send-terminated-fn]
	 (binding [*context-owner* owner]
		 (doseq [ctx (get-all-contexts-for-player owner player-uuid)]
			 (terminate-context! (:id ctx) send-terminated-fn)))))

(defn update-keepalive! [ctx-id]
	(update-context! ctx-id assoc :last-keepalive-ms (System/currentTimeMillis)))
(defn check-keepalive-timeout! [send-terminated-fn]
	(let [now (System/currentTimeMillis)]
		(doseq [[ctx-id ctx] (context-registry-snapshot)]
			(when (and (= (:status ctx) STATUS-ALIVE)
								 (= :server (context-logical-side ctx))
								 (:last-keepalive-ms ctx)
								 (> (- now (:last-keepalive-ms ctx)) (keepalive-timeout-ms)))
				(log/debug "Context keepalive timeout:" ctx-id)
				(record-lifecycle-event! :timeout-terminated ctx)
				(terminate-context! ctx-id send-terminated-fn)))))

(defn purge-terminated-contexts!
	"Remove terminated contexts after a short grace window so client/server
	observers can still read final status immediately after termination."
	[]
	(let [now (System/currentTimeMillis)]
		(update-dispatcher-state!
							 update :context-registry
							 (fn [registry]
								 (into {}
										(remove (fn [[_ctx-id ctx-map]]
												(and (= (:status ctx-map) STATUS-TERMINATED)
														 (:terminated-at-ms ctx-map)
														 (>= (- now (:terminated-at-ms ctx-map)) (terminated-context-grace-ms)))))
										registry)))))

(defn ctx-buffer-or-send!
	([ctx-id msg send-fn]
	 (ctx-buffer-or-send! ctx-id nil msg send-fn))
	([ctx-id preferred-side msg send-fn]
	 (let [ctx (second (preferred-context-entry ctx-id preferred-side))]
		(when ctx
			(case (:status ctx)
				:constructed (update-context! ctx-id update :message-buffer conj msg)
				:alive (when send-fn (send-fn msg))
				nil)))))

(defn ctx-send-to-local! [ctx-id channel msg]
	(when-let [ctx (get-context ctx-id)]
		(doseq [h (get-in ctx [:listeners channel] [])]
			(try (h msg) (catch Exception e (log/warn "Listener threw" (ex-message e)))))))

(defn register-route-fns! [{:keys [to-server to-client to-except-local] :as routes}]
	(if (and (nil? (:logical-side routes))
					 (nil? (:side routes))
					 (nil? (:session-id routes))
					 (nil? to-server)
					 (nil? to-client)
					 (nil? to-except-local))
		(update-dispatcher-state! assoc :route-fns {})
		(update-dispatcher-state! assoc-in [:route-fns (route-owner-key routes)] {:to-server to-server :to-client to-client :to-except-local to-except-local}))
	nil)
(defn ctx-send-to-server! [ctx-id channel msg]
	(ctx-buffer-or-send! ctx-id :client {:channel channel :payload msg}
											 (fn [m] (when-let [ctx (second (preferred-context-entry ctx-id :client))]
															 (when-let [f (:to-server (route-fns-for-context ctx))]
																	 (f ctx-id (:channel m) (:payload m) ctx))))))
(defn ctx-send-to-client! [ctx-id channel msg]
	(ctx-buffer-or-send! ctx-id :server {:channel channel :payload msg}
											 (fn [m] (when-let [ctx (second (preferred-context-entry ctx-id :server))]
															 (when-let [f (:to-client (route-fns-for-context ctx))]
																	 (f ctx-id (:channel m) (:payload m) ctx))))))
(defn ctx-send-to-except-local! [ctx-id channel msg]
	(ctx-buffer-or-send! ctx-id :server {:channel channel :payload msg}
											 (fn [m] (when-let [ctx (second (preferred-context-entry ctx-id :server))]
															 (when-let [f (:to-except-local (route-fns-for-context ctx))]
																	 (f ctx-id (:channel m) (:payload m) ctx))))))
(defn ctx-send-to-self! [ctx-id channel msg] (ctx-send-to-local! ctx-id channel msg))
(defn ctx-on! [ctx-id channel handler-fn] (update-context! ctx-id update-in [:listeners channel] (fnil conj []) handler-fn))

(defn active-context? [ctx]
	(not= STATUS-TERMINATED (:status ctx)))

(defn active-contexts
	([]
	 (->> (context-registry-snapshot)
			(filter (fn [[_ctx-id ctx]] (active-context? ctx)))
			(map (fn [[_key ctx]] [(:id ctx) ctx]))
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