(ns cn.li.mcmod.content
  "Helpers for triggering shared game content initialization via content SPI."
  (:import [cn.li.mcmod.content.spi ContentInitBootstraps]))

(defn ensure-content-init-registered!
  "Best-effort registration of shared content through ServiceLoader SPI.

  Content modules provide a ContentInitBootstrap implementation that registers
  lifecycle hooks into mcmod when discovered."
  []
  (try
    (ContentInitBootstraps/register "ac")
    (catch Throwable _ nil))
  nil)

