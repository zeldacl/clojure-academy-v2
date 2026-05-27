(ns cn.li.fabric1201.adapter.server-context
  "Fabric runtime server holder for adapter-style runtime APIs."
  (:require [cn.li.mc1201.runtime.spi.server-context :as server-context-spi]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.event.lifecycle.v1 ServerLifecycleEvents
            ServerLifecycleEvents$ServerStarted
            ServerLifecycleEvents$ServerStopped]
           [net.minecraft.server MinecraftServer]))

(defonce ^:private current-server* (atom nil))
(defonce ^:private installed? (atom false))
(defonce ^:private spi-registered? (atom false))

(declare install-server-context!)

(defn get-server
  ^MinecraftServer
  []
  @current-server*)

(defn- register-server-context-spi!
  []
  (when (compare-and-set! spi-registered? false true)
    (server-context-spi/register-server-context-impl!
      {:get-current-server get-server
       :install! install-server-context!}))
  nil)

(defn install-server-context!
  []
  (register-server-context-spi!)
  (when (compare-and-set! installed? false true)
    (.register ServerLifecycleEvents/SERVER_STARTED
               (reify ServerLifecycleEvents$ServerStarted
                 (onServerStarted [_ server]
                   (reset! current-server* server)
                   (server-context-spi/notify-server-available! server))))
    (.register ServerLifecycleEvents/SERVER_STOPPED
               (reify ServerLifecycleEvents$ServerStopped
                 (onServerStopped [_ server]
                   (reset! current-server* nil)
                   (server-context-spi/notify-server-unavailable! server))))
    (log/info "Fabric runtime server context installed"))
  nil)
