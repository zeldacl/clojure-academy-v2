(ns cn.li.forge1201.setup.capability-wiring
  "Forge capability registration listener wiring." 
  (:require [cn.li.mcmod.platform.capability :as platform-cap])
  (:import [net.minecraftforge.common.capabilities RegisterCapabilitiesEvent]
           [net.minecraftforge.eventbus.api EventPriority IEventBus]))

(def ^:private event-priority EventPriority/NORMAL)

(defn- consumer
  [f]
  (reify java.util.function.Consumer
    (accept [_ event]
      (f event))))

(defn- add-listener!
  [^IEventBus mod-bus ^Class listener-class f]
  (.addListener mod-bus event-priority false listener-class (consumer f)))

(defn register-capability-listener!
  [^IEventBus mod-bus]
  (add-listener! mod-bus RegisterCapabilitiesEvent
                 (fn [event]
                   (let [^RegisterCapabilitiesEvent event event]
                     (doseq [^Class java-type (distinct (keep (fn [[_key {:keys [java-type]}]]
                                                                java-type)
                                                              @platform-cap/capability-type-registry))]
                       (.register event java-type)))))
  nil)
