(ns cn.li.fabric1201.setup.runtime-setup
  "Fabric runtime setup phase extracted from mod entry."
  (:require [cn.li.fabric1201.runtime.adapters.registry :as runtime-adapters-registry]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]
            [cn.li.fabric1201.gui.init :as gui-init]))

(defn install-runtime!
  []
  (adapter-registry/run-install-steps! "fabric-1.20.1" runtime-adapters-registry/runtime-install-steps)
  (gui-init/init-common!)
  (gui-init/init-server!)
  nil)
