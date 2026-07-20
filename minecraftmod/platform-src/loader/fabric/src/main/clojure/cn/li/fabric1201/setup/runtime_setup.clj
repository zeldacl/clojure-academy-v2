(ns cn.li.fabric1201.setup.runtime-setup
  "Fabric runtime setup phase extracted from mod entry."
  (:require [cn.li.fabric1201.runtime.adapters.registry :as runtime-adapters-registry]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]
            [cn.li.fabric1201.gui.init :as gui-init]
            [cn.li.platform.target :as target]))

(defn install-runtime!
  []
  (adapter-registry/run-install-steps! (:id (target/current-target!)) runtime-adapters-registry/runtime-install-steps)
  (gui-init/init-common!)
  (gui-init/init-server!)
  nil)
