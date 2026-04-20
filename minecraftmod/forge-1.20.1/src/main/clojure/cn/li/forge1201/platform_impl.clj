(ns cn.li.forge1201.platform-impl
  "Facade for Forge platform protocol installation.

  This namespace remains side-effect free during checkClojure loading.
  Real protocol extensions are installed lazily through Java ServiceLoader
  providers when init-platform! is called."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.platform.spi PlatformBootstraps]))


(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations."
  []
  (if (PlatformBootstraps/initialize "forge-1.20.1")
    nil
    (do
      (log/error "No platform bootstrap provider found for forge-1.20.1")
      (throw (ex-info "Forge platform bootstrap provider missing"
                      {:platform "forge-1.20.1"})))))
