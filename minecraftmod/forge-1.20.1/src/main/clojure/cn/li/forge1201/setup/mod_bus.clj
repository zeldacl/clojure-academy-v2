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
	         [net.minecraftforge.common.capabilities RegisterCapabilitiesEvent]
	         [net.minecraftforge.eventbus.api EventPriority IEventBus]
	         [net.minecraftforge.fml InterModComms$IMCMessage]
	         [net.minecraftforge.fml.event.lifecycle FMLClientSetupEvent FMLCommonSetupEvent InterModProcessEvent]
	         [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
	         [net.minecraftforge.registries DeferredRegister]))

(def ^:private event-priority EventPriority/NORMAL)
(def ^:private key-mappings-class-name "net.minecraftforge.client.event.RegisterKeyMappingsEvent")

(defn- consumer
	[f]
	(reify java.util.function.Consumer
		(accept [_ event]
			(f event))))

(defn- add-listener!
	[^IEventBus mod-bus ^Class listener-class f]
	(.addListener mod-bus event-priority false listener-class (consumer f)))

(defn- register-deferred-registries!
	[^IEventBus mod-bus registries]
	(doseq [^DeferredRegister registry registries]
		(.register registry mod-bus)))

(defn- register-common-listeners!
	[mod-bus on-common-setup on-client-setup]
	(add-listener! mod-bus FMLCommonSetupEvent on-common-setup)
	(add-listener! mod-bus FMLClientSetupEvent on-client-setup))

(defn- register-client-hooks!
	[]
	(effect-hooks/register-all-effect-hooks!)
	(ray-hooks/register-all-ray-hooks!)
	(marker-hooks/register-all-marker-hooks!))

(defn- register-gameplay-config!
	[]
	(try
		(let [config-class (Class/forName "cn.li.forge1201.config.GameplayConfig")]
			(.invoke (.getMethod config-class "register" (make-array Class 0)) nil (make-array Object 0)))
		(catch Exception e
			(log/warn "Failed to register gameplay config" e))))

(defn- register-client-key-mappings!
	[mod-bus]
	(try
		(let [rk-class (Class/forName key-mappings-class-name)]
			(add-listener! mod-bus rk-class
							 (fn [event]
							   (when-let [register-keys! (side/resolve-client-fn 'cn.li.forge1201.client.init 'register-key-mappings!)]
								 (register-keys! event))))
			true)
		(catch Exception e
			(log/error "Failed to register key mapping listener" e)
			nil)))

(defn- handle-imc-message!
	[^InterModComms$IMCMessage msg]
	(try
		(let [handler (.get ^java.util.function.Supplier (.getMessageSupplier msg))]
			(condp = (.getMethod msg)
				imc-dispatch/register-topology-network-handler-key
				(imc-dispatch/register-network-handler! handler)
				imc-dispatch/register-topology-node-handler-key
				(imc-dispatch/register-node-handler! handler)
				nil))
		(catch Exception e
			(log/debug "IMC registration failed from"
						 (.getSenderModId msg) ":" (ex-message e)))))

(defn- register-imc-listener!
	[mod-bus]
	(add-listener! mod-bus InterModProcessEvent
					 (fn [event]
					   (let [^InterModProcessEvent event event
								 ^java.util.stream.Stream imc-stream (.getIMCStream event)]
						 (doseq [^InterModComms$IMCMessage msg (iterator-seq (.iterator imc-stream))]
						   (handle-imc-message! msg))))))

(defn- register-capability-listener!
	[mod-bus]
	(add-listener! mod-bus RegisterCapabilitiesEvent
					 (fn [event]
					   (let [^RegisterCapabilitiesEvent event event]
						 (doseq [^Class java-type (distinct (keep (fn [[_key {:keys [java-type]}]]
															 java-type)
														 @platform-cap/capability-type-registry))]
						   (.register event java-type))))))

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
	(let [^IEventBus mod-bus (.getModEventBus (FMLJavaModLoadingContext/get))]
		(register-gameplay-config!)
		(config-bridge/register-all! mod-bus)
		(ModEntities/register mod-bus)
		(when (and (side/client-side?) (not datagen-run?))
			(register-client-hooks!))
		(ModFeatures/register mod-bus)
		(register-deferred-registries! mod-bus [sounds-register
													 effects-register
													 particle-types-register
													 fluid-types-register
													 fluids-register
													 blocks-register
													 items-register
													 block-entities-register
													 creative-tabs-register
													 gui-menu-register])
		(register-common-listeners! mod-bus on-common-setup on-client-setup)
		(when (side/client-side?)
			(register-client-key-mappings! mod-bus))
		(register-imc-listener! mod-bus)
		(register-capability-listener! mod-bus)
		nil))