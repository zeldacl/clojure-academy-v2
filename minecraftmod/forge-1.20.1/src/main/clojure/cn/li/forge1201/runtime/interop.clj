(ns cn.li.forge1201.runtime.interop
  "Forge thin adapter for runtime interop bridge.
  Delegates all MC logic to mc1201 interop-core."
  (:require [cn.li.mc1201.runtime.interop-core :as ic])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server []
  (ServerLifecycleHooks/getCurrentServer))

(defn install-runtime-interop! []
  (ic/install-runtime-interop! "Forge" get-server))