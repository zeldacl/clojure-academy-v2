(ns cn.li.fabric1201.runtime.adapters.install
  "Runtime installer backed by declarative registry."
  (:require [cn.li.fabric1201.runtime.adapters.registry :as registry]))

(defn install-runtime-adapters!
  []
  (doseq [{:keys [install-fn]} registry/runtime-install-steps]
    (install-fn))
  nil)
