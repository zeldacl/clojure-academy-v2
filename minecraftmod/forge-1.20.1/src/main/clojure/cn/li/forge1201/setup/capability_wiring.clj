(ns cn.li.forge1201.setup.capability-wiring
  "Forge capability registration listener wiring." 
  (:require [cn.li.forge1201.setup.consumer-support :as consumer-support]
            [cn.li.mcmod.platform.capability :as platform-cap])
  (:import [net.minecraftforge.common.capabilities RegisterCapabilitiesEvent]
           [net.minecraftforge.eventbus.api IEventBus]))

(defn- add-listener!
  [^IEventBus mod-bus ^Class listener-class f]
  (consumer-support/add-normal-listener! mod-bus listener-class f))

(defn- forge-built-in-capability?
  [^Class java-type]
  (= "net.minecraftforge.energy.IEnergyStorage" (.getName java-type)))

(defn register-capability-listener!
  [^IEventBus mod-bus]
  (add-listener! mod-bus RegisterCapabilitiesEvent
                 (fn [event]
                   (let [^RegisterCapabilitiesEvent event event]
                     (doseq [^Class java-type (distinct (keep (fn [[_key {:keys [java-type]}]]
                                                                java-type)
                                                              (platform-cap/capability-type-registry-snapshot)))]
                       (when-not (forge-built-in-capability? java-type)
                         (.register event java-type))))))
  nil)
