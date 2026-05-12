(ns cn.li.fabric1201.runtime.install
  "Fabric runtime adapter installer.

  Keeps mod entry focused by grouping runtime protocol installation in one place."
  (:require [cn.li.fabric1201.runtime.adapters.install :as adapters-install]))

(defn install-runtime-adapters!
  []
  (adapters-install/install-runtime-adapters!))
