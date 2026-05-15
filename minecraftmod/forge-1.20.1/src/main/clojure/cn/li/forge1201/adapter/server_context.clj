(ns cn.li.forge1201.adapter.server-context
	"Centralized Forge server context accessor for runtime adapters."
	(:require [cn.li.mc1201.runtime.spi.server-context :as server-context-spi])
	(:import [net.minecraft.server MinecraftServer]
					 [net.minecraftforge.server ServerLifecycleHooks]))

(defn get-server
	^MinecraftServer
	[]
	(ServerLifecycleHooks/getCurrentServer))

(defn install-server-context!
	[]
	nil)

(server-context-spi/register-server-context-impl!
	{:get-current-server get-server
	 :install! install-server-context!})
