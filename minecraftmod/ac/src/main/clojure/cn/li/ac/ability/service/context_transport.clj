(ns cn.li.ac.ability.service.context-transport
	"Transport port for ability context server/client messaging.

	Owns send-function registration and provides transport operations used by
	lifecycle/runtime services.")

(defn default-context-transport-runtime-state
	[]
	{:to-client nil
	 :to-server nil
	 :frozen? false})

(defn create-context-transport-runtime
	([] (create-context-transport-runtime {}))
	([{:keys [state*]
		 :or {state* (atom (default-context-transport-runtime-state))}}]
	 {::runtime ::context-transport-runtime
		:state* state*}))

(def ^:dynamic *context-transport-runtime* nil)

(defonce ^:private installed-context-transport-runtime
	(create-context-transport-runtime))

(defn call-with-context-transport-runtime
	[runtime f]
	(when-not (and (map? runtime)
								 (= ::context-transport-runtime (::runtime runtime))
								 (some? (:state* runtime)))
		(throw (ex-info "Expected context transport runtime" {:runtime runtime})))
	(binding [*context-transport-runtime* runtime]
		(f)))

(defn- current-context-transport-runtime
	[]
	(or *context-transport-runtime*
			installed-context-transport-runtime))

(defn- context-transport-state-atom
	[]
	(:state* (current-context-transport-runtime)))

(defn- context-transport-state-snapshot
	[]
	@(context-transport-state-atom))

(defn- update-context-transport-state!
	[f & args]
	(apply swap! (context-transport-state-atom) f args))

(defn- assert-transport-open!
	[]
	(when (:frozen? (context-transport-state-snapshot))
		(throw (ex-info "Context transport is frozen" {}))))

(defn context-transport-snapshot
	[]
	(context-transport-state-snapshot))

(defn reset-context-transport-for-test!
	([]
	 (reset-context-transport-for-test! {}))
	([{:keys [to-client to-server frozen?]
		 :or {to-client nil to-server nil frozen? false}}]
	 (reset! (context-transport-state-atom)
					 {:to-client to-client
						:to-server to-server
						:frozen? frozen?})
	 nil))

(defn freeze-context-transport!
	[]
	(update-context-transport-state! assoc :frozen? true)
	nil)

(defn register-send-fns!
	[{:keys [to-client to-server]}]
	(assert-transport-open!)
	(update-context-transport-state! merge {:to-client to-client :to-server to-server})
	nil)

(defn send-to-client!
	[player-uuid msg-id payload]
	(when-let [f (:to-client (context-transport-state-snapshot))]
		(f player-uuid msg-id payload)))

(defn send-to-server!
	[msg-id payload]
	(when-let [f (:to-server (context-transport-state-snapshot))]
		(f msg-id payload)))