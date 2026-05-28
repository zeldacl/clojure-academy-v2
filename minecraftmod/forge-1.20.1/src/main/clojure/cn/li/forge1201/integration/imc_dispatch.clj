(ns cn.li.forge1201.integration.imc-dispatch
	"IMC support for runtime payload streams.

	External mods register handlers during InterModEnqueueEvent by sending IMC
	messages to this mod using the keys defined here. Handlers are invoked after
	the corresponding runtime payload fires, so they receive the same information
	as a regular EventBus subscriber but via callback.

	Handler isolation: a handler that throws is logged at DEBUG and removed so
	it cannot disrupt subsequent ticks or other handlers."
	(:require [cn.li.mcmod.util.log :as log]))

(def register-topology-network-handler-key "register_topology_network_handler")
(def register-topology-node-handler-key "register_topology_node_handler")

(def ^:private handler-registry-lock
	(Object.))

(def ^:private ^:dynamic *network-handlers* [])
(def ^:private ^:dynamic *node-handlers* [])

(defn- network-handlers-snapshot []
	(var-get #'*network-handlers*))

(defn- node-handlers-snapshot []
	(var-get #'*node-handlers*))

(defn- add-network-handler! [entry]
	(locking handler-registry-lock
		(alter-var-root #'*network-handlers* conj entry)
		nil))

(defn- add-node-handler! [entry]
	(locking handler-registry-lock
		(alter-var-root #'*node-handlers* conj entry)
		nil))

(defn- remove-network-handlers! [bad]
	(locking handler-registry-lock
		(alter-var-root #'*network-handlers* #(remove (set bad) %))
		nil))

(defn- remove-node-handlers! [bad]
	(locking handler-registry-lock
		(alter-var-root #'*node-handlers* #(remove (set bad) %))
		nil))

(defn register-network-handler!
	"Register an IMC topology-network event handler.

	Supported handlers:
	- clojure IFn: either (fn [payload]) or (fn [action ssid matrix])
	- java.util.function.Consumer: receives payload map"
	[handler]
	(cond
		(fn? handler)
		(add-network-handler! {:kind :fn :handler handler})

		(instance? java.util.function.Consumer handler)
		(add-network-handler! {:kind :consumer :handler handler})

		:else
		(log/debug "Ignoring unsupported topology network IMC handler type" (type handler))))

(defn register-node-handler!
	"Register an IMC topology-node event handler.

	Supported handlers:
	- clojure IFn: either (fn [payload]) or (fn [action node])
	- java.util.function.Consumer: receives payload map"
	[handler]
	(cond
		(fn? handler)
		(add-node-handler! {:kind :fn :handler handler})

		(instance? java.util.function.Consumer handler)
		(add-node-handler! {:kind :consumer :handler handler})

		:else
		(log/debug "Ignoring unsupported topology node IMC handler type" (type handler))))

(defn- invoke-safe
	"Call f, return nil. If it throws, log and return ::remove-handler."
	[f]
	(try
		(f)
		nil
		(catch Exception e
			(log/debug "IMC topology handler threw exception, removing it:" (ex-message e))
			::remove-handler)))

(defn- invoke-network-handler! [{:keys [kind handler]} payload]
	(case kind
		:fn (try
					(handler payload)
					(catch clojure.lang.ArityException _
						(handler (:action payload) (:ssid payload) (:matrix payload))))
		:consumer (.accept ^java.util.function.Consumer handler payload)
		nil))

(defn- dispatch-network! [action ssid matrix]
	(let [payload {:kind :topology/network
							 :action action
							 :ssid ssid
							 :matrix matrix}
				bad (keep (fn [h]
									(when (= ::remove-handler
												 (invoke-safe #(invoke-network-handler! h payload)))
										h))
								(network-handlers-snapshot))]
		(when (seq bad)
			(remove-network-handlers! bad))))

(defn- invoke-node-handler! [{:keys [kind handler]} payload]
	(case kind
		:fn (try
					(handler payload)
					(catch clojure.lang.ArityException _
						(handler (:action payload) (:node payload))))
		:consumer (.accept ^java.util.function.Consumer handler payload)
		nil))

(defn- dispatch-node! [action node]
	(let [payload {:kind :topology/node
							 :action action
							 :node node}
				bad (keep (fn [h]
									(when (= ::remove-handler
												 (invoke-safe #(invoke-node-handler! h payload)))
										h))
								(node-handlers-snapshot))]
		(when (seq bad)
			(remove-node-handlers! bad))))

(defn- on-runtime-event [event]
	(when (map? event)
		(case (:kind event)
			:topology/network
			(dispatch-network! (:action event) (:ssid event) (:matrix event))

			:topology/node
			(dispatch-node! (:action event) (:node event))

			nil)))

(defn dispatch-event!
	"Dispatch a runtime payload event to registered IMC handlers."
	[event]
	(on-runtime-event event)
	nil)

(defn init!
	"Initialize IMC payload bridge. Dispatch is bound through platform-events."
	[]
	(log/info "Runtime IMC dispatcher ready (direct payload dispatch mode)"))