(ns cn.li.fabric1201.platform.bootstrap-entry
  "Facade for Fabric platform protocol installation.

  Mirrors Forge 1.20.1 bootstrap style.
  If no SPI provider is present yet, we keep a safe fallback so startup/compile
  can proceed while platform backfills are still in progress."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.platform.spi PlatformBootstraps]))

(defn init-platform!
  "Initialize Fabric 1.20.1 platform implementations.

  Preferred path: ServiceLoader provider via PlatformBootstraps.
  Fallback path: log warning and continue (temporary during migration)."
  []
  (if (PlatformBootstraps/initialize "fabric-1.20.1")
    nil
    (log/warn "No platform bootstrap provider found for fabric-1.20.1; using temporary fallback")))