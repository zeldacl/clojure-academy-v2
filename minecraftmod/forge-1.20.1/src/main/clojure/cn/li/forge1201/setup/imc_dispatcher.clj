(ns cn.li.forge1201.setup.imc-dispatcher
	"Forge mod-bus listener for processing incoming IMC registrations."
	(:require [cn.li.forge1201.integration.imc-dispatch :as imc-dispatch]
						[cn.li.mcmod.util.log :as log])
	(:import [java.util.function Consumer Supplier]
					 [net.minecraftforge.eventbus.api EventPriority IEventBus]
					 [net.minecraftforge.fml.event.lifecycle InterModProcessEvent]))

(def ^:private event-priority EventPriority/NORMAL)

(defn- safe-invoke
	[thunk]
	(try
		(thunk)
		(catch Throwable _
			nil)))

(defn- imc-method-key
	[msg]
	(or (safe-invoke #(.method msg))
			(safe-invoke #(.getMethod msg))))

(defn- imc-supplier
	[msg]
	(or (safe-invoke #(.messageSupplier msg))
			(safe-invoke #(.getMessageSupplier msg))))

(defn- resolve-payload
	[msg]
	(let [supplier (imc-supplier msg)]
		(cond
			(instance? Supplier supplier) (.get ^Supplier supplier)
			(fn? supplier) (supplier)
			:else supplier)))

(defn- handle-imc-message!
	[msg]
	(let [method-key (str (imc-method-key msg))
				payload (resolve-payload msg)]
		(case method-key
			"register_topology_network_handler"
			(imc-dispatch/register-network-handler! payload)

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
			(log/error "Failed to process IMC registrations" t))))

(defn register-imc-listener!
	[^IEventBus mod-bus]
	(.addListener mod-bus
								event-priority
								false
								InterModProcessEvent
								(reify Consumer
									(accept [_ event]
										(handle-imc-process-event! event))))
	nil)