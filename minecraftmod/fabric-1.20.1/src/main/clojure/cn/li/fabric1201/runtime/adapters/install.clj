(ns cn.li.fabric1201.runtime.adapters.install
  "Runtime installer backed by declarative registry."
  (:require [cn.li.fabric1201.runtime.adapters.registry :as registry]
            [cn.li.mc1201.runtime.adapter-registry :as adapter-registry]))

(defn install-runtime-adapters!
  []
  (adapter-registry/run-install-steps! "fabric-1.20.1" registry/runtime-install-steps))
