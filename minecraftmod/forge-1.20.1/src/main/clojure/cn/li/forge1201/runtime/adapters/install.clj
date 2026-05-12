(ns cn.li.forge1201.runtime.adapters.install
  "Installer for declarative Forge runtime adapter registry."
  (:require [cn.li.forge1201.runtime.adapters.registry :as registry]))

(defn install-adapters!
  []
  (doseq [{:keys [install]} registry/adapter-installers]
    (install))
  nil)
