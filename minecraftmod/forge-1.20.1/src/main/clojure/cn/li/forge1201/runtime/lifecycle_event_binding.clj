(ns cn.li.forge1201.runtime.lifecycle-event-binding
	"Forge EventBus binding for runtime player lifecycle listeners."
	(:import [net.minecraftforge.common MinecraftForge]
					 [net.minecraftforge.eventbus.api EventPriority]
					 [net.minecraftforge.event.entity.player PlayerEvent$PlayerLoggedInEvent
																									PlayerEvent$PlayerLoggedOutEvent
																	PlayerEvent$Clone
																	PlayerEvent$PlayerChangedDimensionEvent]
					 [net.minecraftforge.event.entity.living LivingDeathEvent]
					 [net.minecraftforge.event TickEvent$PlayerTickEvent]))

(defonce ^:private lifecycle-listeners-registered? (atom false))

(defn reset-lifecycle-listeners-registration-for-test!
	[]
	(reset! lifecycle-listeners-registered? false)
	nil)

(defn add-listener!
	[event-class handler]
	(.addListener (MinecraftForge/EVENT_BUS)
								EventPriority/NORMAL false event-class
								(reify java.util.function.Consumer
									(accept [_ evt] (handler evt)))))

(defn- listener-bindings
	[{:keys [on-player-login
					 on-player-logout
					 on-player-clone
					 on-player-death
					 on-player-dimension-change
					 on-player-tick]}]
	[[PlayerEvent$PlayerLoggedInEvent on-player-login]
	 [PlayerEvent$PlayerLoggedOutEvent on-player-logout]
	 [PlayerEvent$Clone on-player-clone]
	 [LivingDeathEvent on-player-death]
	 [PlayerEvent$PlayerChangedDimensionEvent on-player-dimension-change]
	 [TickEvent$PlayerTickEvent on-player-tick]])

(defn register-lifecycle-listeners!
	[{:keys [on-player-login
					 on-player-logout
					 on-player-clone
					 on-player-death
					 on-player-dimension-change
					 on-player-tick]}]
	(when (compare-and-set! lifecycle-listeners-registered? false true)
		(doseq [[event-class handler]
					(listener-bindings {:on-player-login on-player-login
													 :on-player-logout on-player-logout
													 :on-player-clone on-player-clone
													 :on-player-death on-player-death
													 :on-player-dimension-change on-player-dimension-change
													 :on-player-tick on-player-tick})]
			(add-listener! event-class handler)))
	nil)