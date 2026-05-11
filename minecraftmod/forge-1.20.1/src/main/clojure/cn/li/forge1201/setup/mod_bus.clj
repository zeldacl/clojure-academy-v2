(ns cn.li.forge1201.setup.mod-bus
	"Forge mod-event-bus wiring extracted from mod entry.

	Owns deferred-register registration and mod lifecycle/event listeners so
	mod.clj stays focused on bootstrap flow."
	(:require [cn.li.forge1201.integration.side :as side]
						[cn.li.forge1201.config.bridge :as config-bridge]
						[cn.li.mc1201.entity.effect-hooks :as effect-hooks]
						[cn.li.mc1201.entity.ray-hooks :as ray-hooks]
						[cn.li.mc1201.entity.marker-hooks :as marker-hooks]
						[cn.li.forge1201.integration.imc-dispatch :as imc-dispatch]
						[cn.li.mcmod.platform.capability :as platform-cap]
						[cn.li.mcmod.util.log :as log])
	(:import [cn.li.forge1201.entity ModEntities]
					 [cn.li.forge1201.worldgen ModFeatures]
					 [net.minecraftforge.eventbus.api EventPriority]
					 [net.minecraftforge.common.capabilities RegisterCapabilitiesEvent]
					 [net.minecraftforge.fml InterModComms$IMCMessage]
					 [net.minecraftforge.fml.event.lifecycle FMLClientSetupEvent FMLCommonSetupEvent InterModProcessEvent]
					 [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
					 [net.minecraftforge.registries DeferredRegister]))

(defn- register-gameplay-config!
	[]
	(try
		(let [config-class (Class/forName "cn.li.forge1201.config.GameplayConfig")]
			(.invoke (.getMethod config-class "register" (make-array Class 0)) nil (make-array Object 0)))
		(catch Exception e
			(log/warn "Failed to register gameplay config" e))))

(defn register-mod-bus!
	[{:keys [datagen-run?
					 on-common-setup
					 on-client-setup
					 sounds-register
					 effects-register
					 particle-types-register
					 fluid-types-register
					 fluids-register
					 blocks-register
					 items-register
					 block-entities-register
					 creative-tabs-register
					 gui-menu-register]}]
	(let [mod-bus (.getModEventBus (FMLJavaModLoadingContext/get))]
		(register-gameplay-config!)
		(config-bridge/register-all! mod-bus)
		(ModEntities/register mod-bus)
		(when (and (side/client-side?) (not datagen-run?))
			(effect-hooks/register-all-effect-hooks!)
			(ray-hooks/register-all-ray-hooks!)
			(marker-hooks/register-all-marker-hooks!))
		(ModFeatures/register mod-bus)
		(.register ^DeferredRegister sounds-register mod-bus)
		(.register ^DeferredRegister effects-register mod-bus)
		(.register ^DeferredRegister particle-types-register mod-bus)
		(.register ^DeferredRegister fluid-types-register mod-bus)
		(.register ^DeferredRegister fluids-register mod-bus)
		(.register ^DeferredRegister blocks-register mod-bus)
		(.register ^DeferredRegister items-register mod-bus)
		(.register ^DeferredRegister block-entities-register mod-bus)
		(.register ^DeferredRegister creative-tabs-register mod-bus)
		(.register ^DeferredRegister gui-menu-register mod-bus)
		(.addListener mod-bus EventPriority/NORMAL false FMLCommonSetupEvent
									(reify java.util.function.Consumer
										(accept [_ event]
											(on-common-setup event))))
		(.addListener mod-bus EventPriority/NORMAL false FMLClientSetupEvent
									(reify java.util.function.Consumer
										(accept [_ event]
											(on-client-setup event))))

		(when (side/client-side?)
			(try
				(let [rk-class (Class/forName "net.minecraftforge.client.event.RegisterKeyMappingsEvent")]
					(.addListener mod-bus EventPriority/NORMAL false rk-class
												(reify java.util.function.Consumer
													(accept [_ event]
														(when-let [register-keys! (side/resolve-client-fn 'cn.li.forge1201.client.init 'register-key-mappings!)]
															(register-keys! event))))))
				(catch Exception e
					(log/error "Failed to register key mapping listener" e))))

		(.addListener mod-bus EventPriority/NORMAL false InterModProcessEvent
									(reify java.util.function.Consumer
										(accept [_ event]
											(let [^InterModProcessEvent event event
														^java.util.stream.Stream imc-stream (.getIMCStream event)]
												(doseq [^InterModComms$IMCMessage msg (iterator-seq (.iterator imc-stream))]
													(try
														(let [handler (.get (.getMessageSupplier msg))]
															(condp = (.getMethod msg)
																imc-dispatch/register-topology-network-handler-key
																(imc-dispatch/register-network-handler! handler)
																imc-dispatch/register-topology-node-handler-key
																(imc-dispatch/register-node-handler! handler)
																nil))
														(catch Exception e
															(log/debug "IMC registration failed from"
																				 (.getSenderModId msg) ":" (ex-message e)))))))))

		(.addListener mod-bus EventPriority/NORMAL false RegisterCapabilitiesEvent
									(reify java.util.function.Consumer
										(accept [_ event]
											(let [^RegisterCapabilitiesEvent event event]
												(doseq [^Class java-type (distinct (keep (fn [[_key {:keys [java-type]}]]
																																	java-type)
																																@platform-cap/capability-type-registry))]
													(.register event java-type))))))
		nil))