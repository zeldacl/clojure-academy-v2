(ns cn.li.fabric1201.runtime.interop
  "Fabric thin adapter for IRuntimeInterop protocol.
  Delegates to mc1201 interop-core using Fabric server-context."
  (:require [cn.li.mc1201.runtime.interop-core :as ic]
            [cn.li.fabric1201.adapter.server-context :as server-ctx]))

(defn install-runtime-interop! []
  (ic/install-runtime-interop! "Fabric" server-ctx/get-server))
