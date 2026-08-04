(ns cn.li.neoforge1211.setup.capability-wiring
  "NeoForge BlockCapability / ItemCapability registration listener wiring."
  (:require [cn.li.neoforge1211.setup.consumer-support :as consumer-support]
            [cn.li.mcmod.capability.registry :as cap-registry])
  (:import [cn.li.neoforge1211.capability CapabilityRegistry
            ForgeCapabilityHandler
            ForgeProvidedCapabilitySupport]
           [net.neoforged.neoforge.capabilities RegisterCapabilitiesEvent]
           [net.neoforged.bus.api IEventBus]))

(defn- add-listener!
  [^IEventBus mod-bus ^Class listener-class f]
  (consumer-support/add-normal-listener! mod-bus listener-class f))

(defn- ensure-block-capability-token!
  "Ensure CapabilityRegistry has a BlockCapability token for this declared capability key."
  [key ^Class java-type]
  (let [k (name key)]
    (when-not (CapabilityRegistry/getBlock k)
      (if-let [provided (ForgeProvidedCapabilitySupport/blockCapabilityForType java-type)]
        (CapabilityRegistry/registerBlock k provided)
        (CapabilityRegistry/getOrCreateBlock k java-type)))
    nil))

(defn- sync-declared-capability-tokens!
  "Map every Clojure-declared capability type onto a NeoForge BlockCapability token."
  []
  (doseq [[key {:keys [java-type]}] (cap-registry/capability-type-registry-snapshot)]
    (when java-type
      (ensure-block-capability-token! key java-type)))
  nil)

(defn register-capability-listener!
  [^IEventBus mod-bus]
  (add-listener! mod-bus RegisterCapabilitiesEvent
                 (fn [event]
                   (let [^RegisterCapabilitiesEvent event event]
                     (sync-declared-capability-tokens!)
                     (ForgeCapabilityHandler/registerAll event))))
  nil)
