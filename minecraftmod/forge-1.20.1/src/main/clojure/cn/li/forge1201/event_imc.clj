(ns cn.li.forge1201.event-imc
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

(defonce ^:private network-handlers (atom []))
(defonce ^:private node-handlers (atom []))

(defn register-network-handler!
	"Register an IMC topology-network event handler.

	Supported handlers:
	- clojure IFn: either (fn [payload]) or (fn [action ssid matrix])
	- java.util.function.Consumer: receives payload map"
	[handler]
	(cond
		(fn? handler)
		(swap! network-handlers conj {:kind :fn :handler handler})

		(instance? java.util.function.Consumer handler)
		(swap! network-handlers conj {:kind :consumer :handler handler})

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
		(swap! node-handlers conj {:kind :fn :handler handler})

		(instance? java.util.function.Consumer handler)
		(swap! node-handlers conj {:kind :consumer :handler handler})

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
									@network-handlers)]
		(when (seq bad)
			(swap! network-handlers #(remove (set bad) %)))))

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
									@node-handlers)]
		(when (seq bad)
			(swap! node-handlers #(remove (set bad) %)))))

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