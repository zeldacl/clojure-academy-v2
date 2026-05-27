(ns cn.li.forge1201.adapter.server-context
	"Centralized Forge server context accessor for runtime adapters."
	(:require [cn.li.mc1201.runtime.spi.server-context :as server-context-spi])
	(:import [net.minecraft.server MinecraftServer]
					 [net.minecraftforge.common MinecraftForge]
					 [net.minecraftforge.event.server ServerStartedEvent ServerStoppedEvent]
					 [net.minecraftforge.eventbus.api EventPriority]
					 [net.minecraftforge.server ServerLifecycleHooks]))

(defn get-server
	^MinecraftServer
	[]
	(ServerLifecycleHooks/getCurrentServer))

(defonce ^:private spi-registered? (atom false))
(defonce ^:private installed? (atom false))

(declare install-server-context!)

(defn- register-server-context-spi!
	[]
	(when (compare-and-set! spi-registered? false true)
		(server-context-spi/register-server-context-impl!
			{:get-current-server get-server
			 :install! install-server-context!}))
	nil)

(defn install-server-context!
	[]
	(register-server-context-spi!)
	(when (compare-and-set! installed? false true)
		(.addListener (MinecraftForge/EVENT_BUS)
							EventPriority/NORMAL false ServerStartedEvent
							(reify java.util.function.Consumer
								(accept [_ evt]
									(server-context-spi/notify-server-available! (.getServer ^ServerStartedEvent evt)))))
		(.addListener (MinecraftForge/EVENT_BUS)
							EventPriority/NORMAL false ServerStoppedEvent
							(reify java.util.function.Consumer
								(accept [_ evt]
									(server-context-spi/notify-server-unavailable! (.getServer ^ServerStoppedEvent evt))))))
	(when-let [server (get-server)]
		(server-context-spi/notify-server-available! server))
	nil)
