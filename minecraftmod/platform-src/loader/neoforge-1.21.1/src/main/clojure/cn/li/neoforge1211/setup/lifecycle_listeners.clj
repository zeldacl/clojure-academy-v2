(ns cn.li.neoforge1211.setup.lifecycle-listeners
  "Lifecycle and client listener registration for Forge mod event bus." 
  (:require [cn.li.neoforgebase.integration.side :as side]
            [cn.li.neoforgebase.setup.consumer-support :as consumer-support]
            [cn.li.mc1211.entity.hooks :as entity-hooks]
            [cn.li.mcmod.util.log :as log])
  (:import [net.neoforged.neoforge.client.event RegisterKeyMappingsEvent]
           [net.neoforged.bus.api IEventBus]))

(defn- add-listener!
  [^IEventBus mod-bus ^Class listener-class f]
  (consumer-support/add-normal-listener! mod-bus listener-class f))

(defn register-client-hooks!
  []
  (entity-hooks/register-all-hooks!)
  nil)

(defn register-client-key-mappings!
  [^IEventBus mod-bus]
  (try
    (add-listener! mod-bus RegisterKeyMappingsEvent
                   (fn [event]
                     (when-let [register-keys! (side/resolve-client-fn 'cn.li.neoforge1211.client.init/register-key-mappings!)]
                       (register-keys! event))))
    true
    (catch Exception e
      (log/error "Failed to register key mapping listener" e)
      nil)))
