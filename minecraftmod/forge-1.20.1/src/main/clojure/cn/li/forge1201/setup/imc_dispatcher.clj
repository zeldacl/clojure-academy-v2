(ns cn.li.forge1201.setup.imc-dispatcher
	"Forge mod-bus listener for processing incoming IMC registrations."
	(:require [cn.li.forge1201.integration.imc-dispatch :as imc-dispatch]
						[cn.li.mcmod.util.log :as log])
	(:import [java.util.function Consumer Supplier]
					 [net.minecraftforge.eventbus.api EventPriority IEventBus]
					 [net.minecraftforge.fml.event.lifecycle InterModProcessEvent]
					 [net.minecraftforge.fml InterModComms$IMCMessage]))

(def ^:private event-priority (delay EventPriority/NORMAL))

(defn- safe-invoke
	[thunk]
	(try
		(thunk)
		(catch Throwable e
			(log/stacktrace "safe-invoke caught exception" e)
			nil)))

(defn- imc-method-key
	[^InterModComms$IMCMessage msg]
	(str (.method msg)))

(defn- imc-supplier
	[^InterModComms$IMCMessage msg]
	(.messageSupplier msg))

(defn- resolve-payload
	[msg]
	(let [supplier (imc-supplier msg)]
		(cond
			(instance? Supplier supplier) (.get ^Supplier supplier)
			(fn? supplier) (supplier)
			:else supplier)))

(defn- handle-imc-message!
	[msg]
	(let [method-key (imc-method-key msg)
				payload (resolve-payload msg)]
		(case method-key
			"register_network_handler"
			"register_topology_network_handler"
			(imc-dispatch/register-network-handler! payload)

			"register_network_node_handler"
			"register_topology_node_handler"
			(imc-dispatch/register-node-handler! payload)

			(log/debug "Ignoring unsupported IMC method" method-key))))

(defn- handle-imc-process-event!
	[^InterModProcessEvent evt]
	(try
		(let [stream (.getIMCStream evt)]
			(.forEach stream
								(reify Consumer
									(accept [_ msg]
										(handle-imc-message! msg)))))
		(catch Throwable t
			(log/error "Failed to process IMC registrations" t)
			(log/stacktrace "handle-imc-process-event! caught exception" t))))

(defn register-imc-listener!
	[^IEventBus mod-bus]
	(.addListener mod-bus
								@event-priority
								false
								InterModProcessEvent
								(reify Consumer
									(accept [_ event]
										(handle-imc-process-event! event))))
	nil)
