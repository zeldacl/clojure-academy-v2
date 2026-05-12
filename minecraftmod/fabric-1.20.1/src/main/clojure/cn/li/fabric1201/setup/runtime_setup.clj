(ns cn.li.fabric1201.setup.runtime-setup
  "Fabric runtime setup phase extracted from mod entry."
  (:require [cn.li.fabric1201.runtime.install :as runtime-install]
            [cn.li.fabric1201.gui.init :as gui-init]))

(defn install-runtime!
  []
  (runtime-install/install-runtime-adapters!)
  (gui-init/init-common!)
  (gui-init/init-server!)
  nil)
