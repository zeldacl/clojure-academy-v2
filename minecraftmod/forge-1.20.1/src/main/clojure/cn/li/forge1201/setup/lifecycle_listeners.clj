(ns cn.li.forge1201.setup.lifecycle-listeners
  "Lifecycle and client listener registration for Forge mod event bus." 
  (:require [cn.li.forge1201.integration.side :as side]
            [cn.li.forge1201.setup.consumer-support :as consumer-support]
            [cn.li.mc1201.entity.hooks :as entity-hooks]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.eventbus.api IEventBus]))

(def ^:private key-mappings-class-name "net.minecraftforge.client.event.RegisterKeyMappingsEvent")

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
    (let [rk-class (Class/forName key-mappings-class-name)]
      (add-listener! mod-bus rk-class
                     (fn [event]
                       (when-let [register-keys! (side/resolve-client-fn 'cn.li.forge1201.client.init 'register-key-mappings!)]
                         (register-keys! event))))
      true)
    (catch Exception e
      (log/error "Failed to register key mapping listener" e)
      nil)))
