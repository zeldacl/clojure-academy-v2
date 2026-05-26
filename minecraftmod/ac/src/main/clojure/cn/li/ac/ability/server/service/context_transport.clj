(ns cn.li.ac.ability.server.service.context-transport
	"Transport port for ability context server/client messaging.

	Owns send-function registration and provides transport operations used by
	lifecycle/runtime services.")

(defonce ^:private send-to-client-fn (atom nil))
(defonce ^:private send-to-server-fn (atom nil))
(defonce ^:private transport-frozen? (atom false))

(defn- assert-transport-open!
	[]
	(when @transport-frozen?
		(throw (ex-info "Context transport is frozen" {}))))

(defn context-transport-snapshot
	[]
	{:to-client @send-to-client-fn
	 :to-server @send-to-server-fn
	 :frozen? @transport-frozen?})

(defn reset-context-transport-for-test!
	([]
	 (reset-context-transport-for-test! {}))
	([{:keys [to-client to-server frozen?]
		 :or {to-client nil to-server nil frozen? false}}]
	 (reset! send-to-client-fn to-client)
	 (reset! send-to-server-fn to-server)
	 (reset! transport-frozen? frozen?)
	 nil))

(defn freeze-context-transport!
	[]
	(reset! transport-frozen? true)
	nil)

(defn register-send-fns!
	[{:keys [to-client to-server]}]
	(assert-transport-open!)
	(reset! send-to-client-fn to-client)
	(reset! send-to-server-fn to-server)
	nil)

(defn send-to-client!
	[player-uuid msg-id payload]
	(when-let [f @send-to-client-fn]
		(f player-uuid msg-id payload)))

(defn send-to-server!
	[msg-id payload]
	(when-let [f @send-to-server-fn]
		(f msg-id payload)))