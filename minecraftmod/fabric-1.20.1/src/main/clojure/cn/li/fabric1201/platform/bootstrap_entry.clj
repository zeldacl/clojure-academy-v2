(ns cn.li.fabric1201.platform.bootstrap-entry
  "Facade for Fabric platform protocol installation.

  Mirrors Forge 1.20.1 bootstrap style."
  (:import [cn.li.mcmod.platform.spi PlatformBootstraps]))

(defn init-platform!
  "Initialize Fabric 1.20.1 platform implementations.

  Preferred path: ServiceLoader provider via PlatformBootstraps.
  Missing provider is a hard failure."
  []
  (if (PlatformBootstraps/initialize "fabric-1.20.1")
    nil
    (throw (ex-info "Fabric platform bootstrap provider missing"
                    {:platform "fabric-1.20.1"}))))