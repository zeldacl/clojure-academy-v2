(ns cn.li.neoforge262.mod
  "NeoForge 26.2 loader entry implemented via Java @Mod bridge."
  (:require [cn.li.neoforgebase.integration.side :as side]
            [cn.li.mcmod.aot :as aot]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.neoforge262.platform.init :as platform-init])
  (:import [cn.li.neoforgebase.bootstrap ForgeBootstrapGuard]
           [cn.li.neoforge262.datagen DatagenBootstrap]
           [net.neoforged.bus.api IEventBus]
           [net.neoforged.fml ModContainer]
           [net.neoforged.fml.event.lifecycle FMLClientSetupEvent
                                              FMLCommonSetupEvent]
           [net.neoforged.neoforge.data.event GatherDataEvent$Client
                                              GatherDataEvent$Server]))

(defn- current-mod-id
  []
  modid/mod-id)

(defn start-neoforge-mod!
  "NeoForge 26.2 bootstrap. Shared NeoForge utilities live in cn.li.neoforgebase.*;
  version-specific registration grows from this entry."
  [^IEventBus mod-event-bus ^ModContainer mod-container]
  (log/info "[neoforge262] start-neoforge-mod!" {:mod-id (current-mod-id)})
  (when (ForgeBootstrapGuard/markModBusRegisteredIfAbsent)
    (log/info "[neoforge262] mod bus first registration")
    ;; GatherDataEvent is abstract on 26.2 — listen to Client/Server subclasses.
    (.addListener mod-event-bus GatherDataEvent$Client
                  (reify java.util.function.Consumer
                    (accept [_ ev]
                      (DatagenBootstrap/onGatherData ev))))
    (.addListener mod-event-bus GatherDataEvent$Server
                  (reify java.util.function.Consumer
                    (accept [_ ev]
                      (DatagenBootstrap/onGatherData ev))))
    (.addListener mod-event-bus FMLCommonSetupEvent
                  (reify java.util.function.Consumer
                    (accept [_ ev]
                      (when (ForgeBootstrapGuard/markCommonSetupCompleteIfAbsent)
                        (log/info "[neoforge262] common setup")
                        (platform-init/init-platform!)))))
    (when (side/client-side?)
      (.addListener mod-event-bus FMLClientSetupEvent
                    (reify java.util.function.Consumer
                      (accept [_ ev]
                        (log/info "[neoforge262] client setup (stub)"))))))
  (aot/ensure-runtime! "cn.li.neoforge262.mod/start-neoforge-mod!")
  nil)
