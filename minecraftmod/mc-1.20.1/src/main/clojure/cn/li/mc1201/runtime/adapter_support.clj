(ns cn.li.mc1201.runtime.adapter-support
  "Small shared helper for installing platform adapter implementations into
  mcmod protocol vars."
  (:require [cn.li.mcmod.util.log :as log]))

(defn install-adapter!
  [adapter-var adapter label]
  (alter-var-root adapter-var (constantly adapter))
  (log/info label "installed"))