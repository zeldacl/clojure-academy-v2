(ns cn.li.fabric1201.adapter.server-context
  "Fabric runtime server holder for adapter-style runtime APIs."
  (:require [cn.li.mc1201.runtime.spi.server-context :as server-context-spi]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.event.lifecycle.v1 ServerLifecycleEvents
            ServerLifecycleEvents$ServerStarted
            ServerLifecycleEvents$ServerStopped]
           [net.minecraft.server MinecraftServer]))

(defn create-server-context-runtime
  ([]
   (create-server-context-runtime nil))
  ([initial-server]
   {:current-server (atom initial-server)}))

(defonce ^:private installed-server-context-runtime
  (create-server-context-runtime))

(def ^:dynamic *server-context-runtime*
  installed-server-context-runtime)

(defn current-server-context-runtime
  []
  *server-context-runtime*)

(defmacro with-server-context-runtime
  [runtime & body]
  `(binding [*server-context-runtime* ~runtime]
     ~@body))

(defn call-with-server-context-runtime
  [runtime f]
  (binding [*server-context-runtime* runtime]
    (f)))

(defn current-server-atom
  []
  (:current-server (current-server-context-runtime)))

(defn current-server-snapshot
  []
  @(current-server-atom))

(defn- set-current-server!
  [server]
  (reset! (current-server-atom) server)
  server)

(defn clear-current-server!
  []
  (set-current-server! nil)
  nil)

(defn reset-current-server-for-test!
  ([]
   (clear-current-server!))
  ([server]
   (set-current-server! server)
   nil))

(def ^:private server-context-guard-lock
  (Object.))

(def ^:private ^:dynamic *installed?* false)
(def ^:private ^:dynamic *spi-registered?* false)

(declare install-server-context!)

(defn get-server
  ^MinecraftServer
  []
  (current-server-snapshot))

(defn- register-server-context-spi!
  []
  (when-not (var-get #'*spi-registered?*)
    (locking server-context-guard-lock
      (when-not (var-get #'*spi-registered?*)
        (server-context-spi/register-server-context-impl!
          {:get-current-server get-server
           :install! install-server-context!})
        (alter-var-root #'*spi-registered?* (constantly true)))))
  nil)

(defn install-server-context!
  []
  (register-server-context-spi!)
  (when-not (var-get #'*installed?*)
    (locking server-context-guard-lock
      (when-not (var-get #'*installed?*)
        (.register ServerLifecycleEvents/SERVER_STARTED
                   (reify ServerLifecycleEvents$ServerStarted
                     (onServerStarted [_ server]
                       (set-current-server! server)
                       (server-context-spi/notify-server-available! server))))
        (.register ServerLifecycleEvents/SERVER_STOPPED
                   (reify ServerLifecycleEvents$ServerStopped
                     (onServerStopped [_ server]
                       (clear-current-server!)
                       (server-context-spi/notify-server-unavailable! server))))
        (alter-var-root #'*installed?* (constantly true))
        (log/info "Fabric runtime server context installed"))))
  nil)
