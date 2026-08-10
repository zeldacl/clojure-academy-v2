(ns cn.li.fabric262.setup.runtime-setup
  "Fabric runtime setup phase extracted from mod entry."
  (:require [cn.li.fabric262.runtime.adapters.registry :as runtime-adapters-registry]
            [cn.li.fabricbase.runtime :as fabric-runtime]
            [cn.li.mcbase.runtime.adapter-registry :as adapter-registry]
            [cn.li.fabric262.gui.init :as gui-init]
            [cn.li.platform.target :as target]))

(defn preload-platform-runtime!
  "Force the AOT runtime-adapter graph to initialize before neutral facade
   roots are installed from validated provider maps."
  []
  nil)

(defn install-runtime!
  []
  (let [runtime-adapters (runtime-adapters-registry/runtime-install-steps)]
    (adapter-registry/run-install-steps! (:id (target/current-target!)) runtime-adapters)
    (gui-init/init-common!)
    (gui-init/init-server!)
    (fabric-runtime/install! {:target (target/current-target!)
                               :runtime-adapters runtime-adapters
                               :gui-init true}))
  nil)
