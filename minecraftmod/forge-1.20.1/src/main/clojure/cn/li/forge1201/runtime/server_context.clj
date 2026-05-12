(ns cn.li.forge1201.runtime.server-context
	"Centralized Forge server context accessor for runtime adapters."
	(:import [net.minecraft.server MinecraftServer]
					 [net.minecraftforge.server ServerLifecycleHooks]))

(defn get-server
	^MinecraftServer
	[]
	(ServerLifecycleHooks/getCurrentServer))