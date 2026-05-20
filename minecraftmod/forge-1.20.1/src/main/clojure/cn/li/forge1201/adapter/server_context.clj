(ns cn.li.forge1201.adapter.server-context
	"Centralized Forge server context accessor for runtime adapters."
	(:require [cn.li.mc1201.runtime.spi.server-context :as server-context-spi])
	(:import [net.minecraft.server MinecraftServer]
					 [net.minecraftforge.server ServerLifecycleHooks]))

(defn get-server
	^MinecraftServer
	[]
	(ServerLifecycleHooks/getCurrentServer))

(defonce ^:private spi-registered? (atom false))

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
	(when-let [server (get-server)]
		(server-context-spi/notify-server-available! server))
	nil)
