(ns cn.li.fabric1201.runtime.server-context
  "Fabric runtime server holder for adapter-style runtime APIs."
  (:require [cn.li.mc1201.runtime.server-context-spi :as server-context-spi]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.event.lifecycle.v1 ServerLifecycleEvents
            ServerLifecycleEvents$ServerStarted
            ServerLifecycleEvents$ServerStopped]
           [net.minecraft.server MinecraftServer]))

(defonce ^:private current-server* (atom nil))
(defonce ^:private installed? (atom false))

(defn get-server
  ^MinecraftServer
  []
  @current-server*)

(defn install-server-context!
  []
  (when (compare-and-set! installed? false true)
    (.register ServerLifecycleEvents/SERVER_STARTED
               (reify ServerLifecycleEvents$ServerStarted
                 (onServerStarted [_ server]
                   (reset! current-server* server))))
    (.register ServerLifecycleEvents/SERVER_STOPPED
               (reify ServerLifecycleEvents$ServerStopped
                 (onServerStopped [_ _server]
                   (reset! current-server* nil))))
    (log/info "Fabric runtime server context installed")))

(server-context-spi/register-server-context-impl!
  {:get-current-server get-server
   :install! install-server-context!})
