(ns cn.li.forge1201.integration.imc-dispatch
	"IMC support for runtime payload streams.

	External mods register handlers during InterModEnqueueEvent by sending IMC
	messages to this mod using the keys defined here. Handlers are invoked after
	the corresponding runtime payload fires, so they receive the same information
	as a regular EventBus subscriber but via callback.

	Handler isolation: a handler that throws is logged at WARN and removed so
	it cannot disrupt subsequent ticks or other handlers."
	(:require [cn.li.mcmod.util.log :as log]))

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
	[handler-entry f]
	(try
		(f)
		nil
		(catch Exception e
			(log/warn "IMC topology handler threw exception, removing it:"
								(pr-str (get handler-entry :kind)) (type (get handler-entry :handler))
								"-" (ex-message e))
			::remove-handler)))

(defn- invoke-network-handler! [{:keys [kind handler]} payload]
	(case kind
		:fn (try
					(handler payload)
					(catch clojure.lang.ArityException _
						(handler (:action payload) (:ssid payload) (:matrix payload))))
		:consumer (.accept ^java.util.function.Consumer handler payload)
		nil))

(defn- dispatch-network! [payload]
	(let [bad (keep (fn [h]
									(when (= ::remove-handler
												 (invoke-safe h #(invoke-network-handler! h payload)))
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

(defn- dispatch-node! [payload]
	(let [bad (keep (fn [h]
									(when (= ::remove-handler
												 (invoke-safe h #(invoke-node-handler! h payload)))
										h))
								(node-handlers-snapshot))]
		(when (seq bad)
			(remove-node-handlers! bad))))

(defn- on-runtime-event
	"Forward the full event map — device link events carry :generator/:receiver
	caps alongside :node; handlers get everything the internal event has."
	[event]
	(when (map? event)
		(case (get event :kind)
			:topology/network (dispatch-network! event)
			:topology/node (dispatch-node! event)
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