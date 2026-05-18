(ns cn.li.mcmod.content
  "Helpers for triggering shared game content initialization via content SPI."
  (:import [cn.li.mcmod.content.spi ContentInitBootstraps]))

(defn register-content!
  "Best-effort registration of a shared content module through ServiceLoader SPI.

  Content modules provide a ContentInitBootstrap implementation that registers
  lifecycle hooks into mcmod when discovered. The content id is supplied by the
  platform/datagen caller so mcmod stays content-agnostic."
  [content-id]
  (try
    (ContentInitBootstraps/register (str content-id))
    (catch Throwable t
      (println (str "[my_mod] WARNING: ContentInitBootstraps/register(" content-id ") failed:")
               (ex-message t))
      nil))
  nil)

