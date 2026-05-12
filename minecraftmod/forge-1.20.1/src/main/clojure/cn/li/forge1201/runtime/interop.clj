(ns cn.li.forge1201.runtime.interop
  "Forge thin adapter for runtime interop bridge.
  Delegates all MC logic to mc1201 interop-core."
  (:require [cn.li.mc1201.runtime.interop-core :as ic]
            [cn.li.forge1201.runtime.server-context :as server-context]))

(defn install-runtime-interop! []
  (ic/install-runtime-interop! "Forge" server-context/get-server))