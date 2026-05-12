(ns cn.li.forge1201.setup.lifecycle-listeners
  "Lifecycle and client listener registration for Forge mod event bus." 
  (:require [cn.li.forge1201.integration.side :as side]
            [cn.li.forge1201.setup.consumer-support :as consumer-support]
            [cn.li.mc1201.entity.effect-hooks :as effect-hooks]
            [cn.li.mc1201.entity.ray-hooks :as ray-hooks]
            [cn.li.mc1201.entity.marker-hooks :as marker-hooks]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.eventbus.api IEventBus]
           [net.minecraftforge.fml.event.lifecycle FMLClientSetupEvent FMLCommonSetupEvent]))

(def ^:private key-mappings-class-name "net.minecraftforge.client.event.RegisterKeyMappingsEvent")

(defn- add-listener!
  [^IEventBus mod-bus ^Class listener-class f]
  (consumer-support/add-normal-listener! mod-bus listener-class f))

(defn register-common-lifecycle-listeners!
  [^IEventBus mod-bus on-common-setup on-client-setup]
  (add-listener! mod-bus FMLCommonSetupEvent on-common-setup)
  (add-listener! mod-bus FMLClientSetupEvent on-client-setup)
  nil)

(defn register-client-hooks!
  []
  (effect-hooks/register-all-effect-hooks!)
  (ray-hooks/register-all-ray-hooks!)
  (marker-hooks/register-all-marker-hooks!)
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
