(ns cn.li.forge1201.runtime.lifecycle-event-binding
	"Forge EventBus binding for runtime player lifecycle listeners."
	(:import [net.minecraftforge.common MinecraftForge]
					 [net.minecraftforge.eventbus.api EventPriority]
					 [net.minecraftforge.event.entity.player PlayerEvent$PlayerLoggedInEvent
																									PlayerEvent$PlayerLoggedOutEvent
																									PlayerEvent$Clone]
					 [net.minecraftforge.event.entity.living LivingDeathEvent]
					 [net.minecraftforge.event TickEvent$PlayerTickEvent]))

(defn register-lifecycle-listeners!
	[{:keys [on-player-login
					 on-player-logout
					 on-player-clone
					 on-player-death
					 on-player-tick]}]
	(.addListener (MinecraftForge/EVENT_BUS)
								EventPriority/NORMAL false PlayerEvent$PlayerLoggedInEvent
								(reify java.util.function.Consumer
									(accept [_ evt] (on-player-login evt))))
	(.addListener (MinecraftForge/EVENT_BUS)
								EventPriority/NORMAL false PlayerEvent$PlayerLoggedOutEvent
								(reify java.util.function.Consumer
									(accept [_ evt] (on-player-logout evt))))
	(.addListener (MinecraftForge/EVENT_BUS)
								EventPriority/NORMAL false PlayerEvent$Clone
								(reify java.util.function.Consumer
									(accept [_ evt] (on-player-clone evt))))
	(.addListener (MinecraftForge/EVENT_BUS)
								EventPriority/NORMAL false LivingDeathEvent
								(reify java.util.function.Consumer
									(accept [_ evt] (on-player-death evt))))
	(.addListener (MinecraftForge/EVENT_BUS)
								EventPriority/NORMAL false TickEvent$PlayerTickEvent
								(reify java.util.function.Consumer
									(accept [_ evt] (on-player-tick evt))))
	nil)