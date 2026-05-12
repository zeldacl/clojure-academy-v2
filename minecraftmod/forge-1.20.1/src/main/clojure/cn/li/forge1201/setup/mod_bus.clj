(ns cn.li.forge1201.setup.mod-bus
	"Forge mod-event-bus wiring extracted from mod entry.

	Owns deferred-register registration and mod lifecycle/event listeners so
	mod.clj stays focused on bootstrap flow."
	(:require [cn.li.forge1201.integration.side :as side]
				[cn.li.forge1201.config.bridge :as config-bridge]
				[cn.li.forge1201.setup.deferred-registries :as deferred-registries]
				[cn.li.forge1201.setup.lifecycle-listeners :as lifecycle-listeners]
				[cn.li.forge1201.setup.imc-dispatcher :as setup-imc-dispatcher]
				[cn.li.forge1201.setup.capability-wiring :as capability-wiring]
				[cn.li.mcmod.util.log :as log])
	(:import [cn.li.forge1201.entity ModEntities]
	         [cn.li.forge1201.worldgen ModFeatures]
	         [net.minecraftforge.eventbus.api IEventBus]
	         [net.minecraftforge.fml.javafmlmod FMLJavaModLoadingContext]
	         ))

(defn- register-gameplay-config!
	[]
	(try
		(let [config-class (Class/forName "cn.li.forge1201.config.GameplayConfig")]
			(.invoke (.getMethod config-class "register" (make-array Class 0)) nil (make-array Object 0)))
		(catch Exception e
			(log/warn "Failed to register gameplay config" e))))

(defn- resolve-mod-bus
	[]
	(.getModEventBus (FMLJavaModLoadingContext/get)))

(defn register-config-phase!
	[^IEventBus mod-bus _opts]
	(register-gameplay-config!)
	(config-bridge/register-all! mod-bus)
	nil)

(defn register-registry-phase!
	[^IEventBus mod-bus {:keys [datagen-run?
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
	(ModEntities/register mod-bus)
	(when (and (side/client-side?) (not datagen-run?))
		(lifecycle-listeners/register-client-hooks!))
	(ModFeatures/register mod-bus)
	(deferred-registries/register-deferred-registries! mod-bus [sounds-register
																		 effects-register
																		 particle-types-register
																		 fluid-types-register
																		 fluids-register
																		 blocks-register
																		 items-register
																		 block-entities-register
																		 creative-tabs-register
																		 gui-menu-register])
	nil)

(defn register-lifecycle-phase!
	[^IEventBus mod-bus {:keys [on-common-setup on-client-setup]}]
	(lifecycle-listeners/register-common-lifecycle-listeners! mod-bus on-common-setup on-client-setup)
	(when (side/client-side?)
		(lifecycle-listeners/register-client-key-mappings! mod-bus))
	nil)

(defn register-capability-phase!
	[^IEventBus mod-bus _opts]
	(setup-imc-dispatcher/register-imc-listener! mod-bus)
	(capability-wiring/register-capability-listener! mod-bus)
	nil)

(defn registration-phase-plan
	[_opts]
	[[:config register-config-phase!]
	 [:registry register-registry-phase!]
	 [:lifecycle register-lifecycle-phase!]
	 [:capability register-capability-phase!]])

(defn run-registration-phases!
	[opts]
	(let [^IEventBus mod-bus (resolve-mod-bus)]
		(doseq [[_phase phase-fn] (registration-phase-plan opts)]
			(phase-fn mod-bus opts))
		nil))

(defn register-mod-bus!
	[opts]
	(run-registration-phases! opts))