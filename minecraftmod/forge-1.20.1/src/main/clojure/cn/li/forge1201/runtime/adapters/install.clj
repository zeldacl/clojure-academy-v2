(ns cn.li.forge1201.runtime.adapters.install
  "Installer for declarative Forge runtime adapter registry."
  (:require [cn.li.forge1201.runtime.adapters.registry :as registry]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]))

(defn install-adapters!
  []
  (adapter-registry/run-install-steps! "forge-1.20.1" registry/runtime-install-steps))
