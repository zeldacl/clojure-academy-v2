(ns cn.li.ac.ability.service.context-dispatcher
	"Context transport router: listeners, routes, id counters, message buffering.

	Business context state (status, input-state, skill-state, keepalive) is
	authoritative in runtime-store and updated only via reducer commands."
	(:require [cn.li.ac.ability.registry.event :as evt]
						[cn.li.ac.ability.service.command-runtime :as command-rt]
						[cn.li.ac.ability.service.context-domain :as context-domain]
						[cn.li.ac.ability.service.context-projection :as ctx-proj]
						[cn.li.mcmod.util.log :as log]))

(def STATUS-CONSTRUCTED context-domain/status-constructed)
(def STATUS-ALIVE context-domain/status-alive)
(def STATUS-TERMINATED context-domain/status-terminated)

(defn status-valid-transition? [from to]
	(context-domain/status-valid-transition? from to))

(def ^:private STATIC-ROUTE-OWNER [::static-routes ::process])

(def ^:dynamic *context-owner*
	"Explicit runtime owner for resolving otherwise opaque ctx-id values within
	a request/callback stack. Server handlers bind this to a player-owned session
	so identical client-generated context ids from different players do not
	collide in multiplayer."
	nil)

(def ^:private default-dispatcher-state
	{:transport-contexts {}
	 :route-fns {}
	 :client-id-counter {}
	 :server-id-counter {}})

(defn create-dispatcher-runtime
	[]
	{::runtime ::dispatcher-runtime
	 :dispatcher-state* (atom default-dispatcher-state)})

(def ^:dynamic *dispatcher-runtime* nil)
(defonce ^:private dispatcher-runtime-ref*
	(atom (create-dispatcher-runtime)))

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
			@dispatcher-runtime-ref*))

(defn install-dispatcher-runtime!
	"Install an explicit dispatcher runtime instance.
	This enables composition-root wiring instead of namespace-level singletons."
	[runtime]
	(when-not (dispatcher-runtime? runtime)
		(throw (ex-info "Expected dispatcher runtime"
						{:runtime runtime})))
	(reset! dispatcher-runtime-ref* runtime)
	runtime)

(defn use-fresh-dispatcher-runtime!
	"Reset dispatcher runtime installation to a fresh runtime instance."
	[]
	(let [runtime (create-dispatcher-runtime)]
		(reset! dispatcher-runtime-ref* runtime)
		runtime))

(defn- dispatcher-state-atom
	[]
	(:dispatcher-state* (current-dispatcher-runtime)))

(defn- dispatcher-state-snapshot
	[]
	@(dispatcher-state-atom))

(defn- update-dispatcher-state!
	[f & args]
	(apply swap! (dispatcher-state-atom) f args))

(defn- transport-contexts-snapshot
	[]
	(:transport-contexts (dispatcher-state-snapshot)))

(declare context-session-id)

(defn- context-store-session-id
	[ctx]
	(let [sid (context-session-id ctx)]
		(if (vector? sid) (first sid) sid)))

(defn- context-store-player-uuid
	[ctx]
	(or (:player-uuid ctx)
			(when-let [sid (context-session-id ctx)]
				(when (vector? sid)
					(second sid)))))

(defn- run-context-status-command!
	[ctx status reason]
	(let [session-id (context-store-session-id ctx)
				player-uuid (context-store-player-uuid ctx)]
		(when (and session-id player-uuid)
			(command-rt/run-command-in-session!
			 session-id
			 player-uuid
			 {:command :update-context-status
				:ctx-id (:id ctx)
				:status status
				:reason reason}))))

(defn- route-fns-snapshot
	[]
	(:route-fns (dispatcher-state-snapshot)))

(defn- assoc-transport!
	[key ctx]
	(update-dispatcher-state! assoc-in [:transport-contexts key] ctx)
	ctx)

(defn- update-transport-if-present!
	[key f & args]
	(update-dispatcher-state!
	 (fn [state]
		 (if (contains? (:transport-contexts state) key)
			 (apply update-in state [:transport-contexts key] f args)
			 state))))

(defn- dissoc-transport!
	[key]
	(update-dispatcher-state! update :transport-contexts dissoc key)
	nil)

(defn- clear-route-fns!
	[]
	(update-dispatcher-state! assoc :route-fns {})
	nil)

(defn- register-route-fns-entry!
	[owner-key routes]
	(update-dispatcher-state! assoc-in [:route-fns owner-key] routes)
	nil)

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


(defn- resolve-context-owner
	[owner ctx-id preferred-side]
	(let [resolved-owner (or owner *context-owner*)]
		(when-not resolved-owner
			(throw (ex-info "Opaque ctx-id resolution requires *context-owner* or an explicit owner"
							{:ctx-id ctx-id
							 :preferred-side preferred-side})))
		(cond-> resolved-owner
			preferred-side (assoc :logical-side preferred-side))))

(defn- preferred-context-entry
	([ctx-id]
	 (preferred-context-entry nil ctx-id nil))
	([ctx-id preferred-side]
	 (preferred-context-entry nil ctx-id preferred-side))
	([owner ctx-id preferred-side]
	 (if (vector? ctx-id)
		 (when-let [ctx (get (transport-contexts-snapshot) ctx-id)]
			 [ctx-id ctx])
		 (let [resolved-owner (resolve-context-owner owner ctx-id preferred-side)
				 [side session] (route-owner-key resolved-owner)
					 key [side session ctx-id]]
				 (when-let [ctx (get (transport-contexts-snapshot) key)]
				 [key ctx])))))

(defn- route-fns-for-context
	[ctx]
	(let [side-key [(context-logical-side ctx) (context-session-id ctx)]
				 session-any-key [:any (context-session-id ctx)]]
		(or (get (route-fns-snapshot) side-key)
				(get (route-fns-snapshot) session-any-key)
				(get (route-fns-snapshot) STATIC-ROUTE-OWNER)
				{:to-server nil :to-client nil :to-except-local nil})))

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
	(let [ctx* (context-with-owner ctx)
				session-id (context-store-session-id ctx*)
				player-uuid (context-store-player-uuid ctx*)]
		(when (and session-id player-uuid)
			(command-rt/run-command-in-session!
			 session-id
			 player-uuid
			 {:command :register-context
				:ctx-id (:id ctx*)
				:skill-id (:skill-id ctx*)
				:status (or (:status ctx*) STATUS-CONSTRUCTED)}))
		(assoc-transport! (context-registry-key ctx*) ctx*)
		ctx*))

(defn get-context
	([ctx-id]
	 (some-> (second (preferred-context-entry nil ctx-id nil))
				 ctx-proj/merge-store-projection))
	([owner ctx-id]
	 (if (vector? ctx-id)
			 (some-> (get (transport-contexts-snapshot) ctx-id)
						 ctx-proj/merge-store-projection)
		 (let [[side session] (route-owner-key owner)]
				 (some-> (get (transport-contexts-snapshot) [side session ctx-id])
							 ctx-proj/merge-store-projection)))))

(defn get-all-contexts
	[]
	(into {}
			(map (fn [[k v]] [k (ctx-proj/merge-store-projection v)]))
			(transport-contexts-snapshot)))

(defn remove-context!
	([ctx-id]
	 (remove-context! nil ctx-id))
	([owner ctx-id]
	 (if (vector? ctx-id)
			 (dissoc-transport! ctx-id)
		 (when-let [[key _ctx] (preferred-context-entry owner ctx-id nil)]
				 (dissoc-transport! key)))))

(defn- owner-matches-context?
	[owner ctx]
	(let [[logical-side session-id] (route-owner-key owner)]
		(and (= logical-side (context-logical-side ctx))
			 (= session-id (context-session-id ctx)))))

(defn get-all-contexts-for-player
	([player-uuid]
	 (filter #(= player-uuid (:player-uuid %))
				 (map ctx-proj/merge-store-projection (vals (transport-contexts-snapshot)))))
	([owner player-uuid]
	 (filter #(and (= player-uuid (:player-uuid %))
					 (owner-matches-context? owner %))
			 (map ctx-proj/merge-store-projection (vals (transport-contexts-snapshot))))))

(defn snapshot-context-registry []
	(get-all-contexts))

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
											 (:transport-contexts state))]
					(-> state
							(assoc :transport-contexts filtered-contexts)
							(update :client-id-counter dissoc owner-key)
							(update :server-id-counter dissoc owner-key)))))
	nil))

(defn clear-session-contexts!
	"Clear all registered contexts and counters for one session id."
	[session-id]
	(update-dispatcher-state!
		(fn [state]
			(-> state
					(update :transport-contexts
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
											counters))))))
	nil)

(defn reset-contexts-for-test!
	([]
	 (reset-contexts-for-test! {}))
	([contexts]
	 (update-dispatcher-state!
		(fn [state]
			(-> state
					(assoc :transport-contexts contexts)
					(assoc :client-id-counter {})
					(assoc :server-id-counter {}))))
	 nil))

(defn reset-route-fns-for-test!
	[]
	(clear-route-fns!)
	nil)

(defn context-owned-by?
	[ctx-id player-uuid]
	(when-let [ctx (get-context ctx-id)]
		(= player-uuid (:player-uuid ctx))))


(defn transition-to-alive!
	([ctx-id server-id flush-fn]
	 (transition-to-alive! nil ctx-id server-id flush-fn))
	([owner ctx-id server-id flush-fn]
	 (when-let [[_key ctx] (preferred-context-entry owner ctx-id :client)]
		 (let [merged (ctx-proj/merge-store-projection ctx)]
			 (when (status-valid-transition? (:status merged) STATUS-ALIVE)
				 (run-context-status-command! merged STATUS-ALIVE nil)
				 (update-transport-if-present!
					_key
					(fn [c]
						(let [buffer (:message-buffer c)
									alive-transport (context-domain/transition-to-alive
																	 c server-id (System/currentTimeMillis))]
							(when (and flush-fn (seq buffer))
								(doseq [msg buffer] (flush-fn msg)))
							alive-transport))))
				 (get-context owner ctx-id)))))

(defn terminate-context!
	([ctx-id send-terminated-fn]
	 (terminate-context! nil ctx-id send-terminated-fn))
	([owner ctx-id send-terminated-fn]
	 (when-let [ctx (or (get-context owner ctx-id)
									(when (nil? owner) (get-context ctx-id)))]
		 (when (not= (:status ctx) STATUS-TERMINATED)
			 (run-context-status-command! ctx STATUS-TERMINATED :dispatcher-terminated)
			 (when-let [[key _transport] (preferred-context-entry owner ctx-id nil)]
				 (update-transport-if-present!
					key
					context-domain/transition-to-terminated
					(System/currentTimeMillis)))
			 (when send-terminated-fn
				 (binding [*context-owner* {:logical-side (context-logical-side ctx)
													 :session-id (context-session-id ctx)}]
					 (send-terminated-fn (:id ctx))))
			 (log/debug "Context terminated:" (:id ctx))))))

(defn abort-all-contexts-for-player!
	([player-uuid send-terminated-fn]
	 (doseq [ctx (get-all-contexts-for-player player-uuid)]
		 (terminate-context! (:id ctx) send-terminated-fn)))
	([owner player-uuid send-terminated-fn]
	 (doseq [ctx (get-all-contexts-for-player owner player-uuid)]
		 (terminate-context! owner (:id ctx) send-terminated-fn))))

(defn ctx-buffer-or-send!
	([ctx-id msg send-fn]
	 (ctx-buffer-or-send! nil ctx-id nil msg send-fn))
	([ctx-id preferred-side msg send-fn]
	 (ctx-buffer-or-send! nil ctx-id preferred-side msg send-fn))
	([owner ctx-id preferred-side msg send-fn]
	 (let [ctx (second (preferred-context-entry owner ctx-id preferred-side))]
		 (when ctx
			 (let [merged (ctx-proj/merge-store-projection ctx)]
				 (case (:status merged)
					 :constructed (when-let [[key _] (preferred-context-entry owner ctx-id preferred-side)]
														(update-transport-if-present! key update :message-buffer conj msg))
					 :alive (when send-fn (send-fn msg))
					 nil))))))

(defn ctx-send-to-local!
	([ctx-id channel msg]
	 (ctx-send-to-local! nil ctx-id channel msg))
	([owner ctx-id channel msg]
	 (when-let [ctx (if owner (get-context owner ctx-id) (get-context ctx-id))]
		 (doseq [h (get-in ctx [:listeners channel] [])]
			 (try (h msg) (catch Exception e (log/warn "Listener threw" (ex-message e))))))))

(defn register-route-fns! [{:keys [to-server to-client to-except-local] :as routes}]
	(if (and (nil? (:logical-side routes))
					 (nil? (:side routes))
					 (nil? (:session-id routes))
					 (nil? to-server)
					 (nil? to-client)
					 (nil? to-except-local))
		(clear-route-fns!)
		(register-route-fns-entry!
			(route-owner-key routes)
			{:to-server to-server :to-client to-client :to-except-local to-except-local}))
	nil)

(defn ctx-send-to-server!
	([ctx-id channel msg]
	 (ctx-send-to-server! nil ctx-id channel msg))
	([owner ctx-id channel msg]
	 (ctx-buffer-or-send! owner ctx-id :client {:channel channel :payload msg}
													(fn [m] (when-let [ctx (second (preferred-context-entry owner ctx-id :client))]
																				(when-let [f (:to-server (route-fns-for-context ctx))]
																						(f ctx-id (:channel m) (:payload m) ctx)))))))

(defn ctx-send-to-client!
	([ctx-id channel msg]
	 (ctx-send-to-client! nil ctx-id channel msg))
	([owner ctx-id channel msg]
	 (ctx-buffer-or-send! owner ctx-id :server {:channel channel :payload msg}
													(fn [m] (when-let [ctx (second (preferred-context-entry owner ctx-id :server))]
																				(when-let [f (:to-client (route-fns-for-context ctx))]
																						(f ctx-id (:channel m) (:payload m) ctx)))))))

(defn ctx-send-to-except-local!
	([ctx-id channel msg]
	 (ctx-send-to-except-local! nil ctx-id channel msg))
	([owner ctx-id channel msg]
	 (ctx-buffer-or-send! owner ctx-id :server {:channel channel :payload msg}
													(fn [m] (when-let [ctx (second (preferred-context-entry owner ctx-id :server))]
																				(when-let [f (:to-except-local (route-fns-for-context ctx))]
																						(f ctx-id (:channel m) (:payload m) ctx)))))))
(defn ctx-send-to-self! [ctx-id channel msg] (ctx-send-to-local! ctx-id channel msg))
(defn ctx-on!
	([ctx-id channel handler-fn]
	 (ctx-on! nil ctx-id channel handler-fn))
	([owner ctx-id channel handler-fn]
	 (when-let [[key _ctx] (preferred-context-entry owner ctx-id nil)]
		 (update-transport-if-present! key update-in [:listeners channel] (fnil conj []) handler-fn))))

(defn active-context? [ctx]
	(context-domain/active-context? ctx))

(defn active-contexts
	([]
	 (->> (transport-contexts-snapshot)
			(map (fn [[_key ctx]] (ctx-proj/merge-store-projection ctx)))
			(filter active-context?)
			(map (fn [ctx] [(:id ctx) ctx]))
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
		 (throw (ex-info "Unsupported context message direction"
						{:ctx-id ctx-id
						 :direction direction
						 :allowed [:to-server :to-client :to-except-local :to-self]})))))

(defn dispatch-skill-event!
	([event]
	 (evt/fire-ability-event! event))
	([skill-id callback-key event]
	 (evt/fire-ability-event!
		 {:skill-id skill-id
			:callback-key callback-key
			:event event})))