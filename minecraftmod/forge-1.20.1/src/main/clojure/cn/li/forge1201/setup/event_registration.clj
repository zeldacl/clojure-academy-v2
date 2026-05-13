(ns cn.li.forge1201.setup.event-registration
	"Unified Forge event registration binder.

	Consumes declarative manifest entries and binds them to ModEventBus / Forge EVENT_BUS."
	(:require [cn.li.forge1201.integration.side :as side]
						[cn.li.forge1201.setup.consumer-support :as consumer-support]
						[cn.li.forge1201.setup.event-registration-manifest :as manifest]
						[cn.li.forge1201.setup.lifecycle-listeners :as lifecycle-listeners])
	(:import [net.minecraftforge.common MinecraftForge]
					 [net.minecraftforge.eventbus.api IEventBus]))

(defn- resolve-handler
	[handler]
	(if (symbol? handler)
		(requiring-resolve handler)
		handler))

(defn- bind-listener-spec!
	[event-bus {:keys [listener-class handler]}]
	(consumer-support/add-normal-listener! event-bus listener-class (resolve-handler handler))
	nil)

(defn register-lifecycle-phase!
	[^IEventBus mod-bus opts]
	(doseq [spec (manifest/lifecycle-listener-specs opts)]
		(bind-listener-spec! mod-bus spec))
	(when (side/client-side?)
		(lifecycle-listeners/register-client-key-mappings! mod-bus))
	nil)

(defn register-common-event-listeners!
	[]
	(doseq [spec (manifest/common-event-listener-specs)]
		(bind-listener-spec! (MinecraftForge/EVENT_BUS) spec))
	nil)