(ns cn.li.mcmod.content
  "Helpers for triggering shared game content initialization via content SPI."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.content.spi ContentInitBootstraps]))

(defn register-content!
  "Register a shared content module through the ServiceLoader SPI.

  Content modules provide a ContentInitBootstrap implementation that explicitly
  registers lifecycle hooks into mcmod when discovered. The content id is supplied by the
  platform/datagen caller so mcmod stays content-agnostic."
  [content-id]
  (try
    (when-not (ContentInitBootstraps/register (str content-id))
      (println (str "[" modid/mod-id "] WARNING: no content bootstrap found for " content-id)))
    (catch Throwable t
      (println (str "[" modid/mod-id "] WARNING: ContentInitBootstraps/register(" content-id ") failed:")
               (ex-message t))
      nil))
  nil)

(defn available-content-ids
  "Return content ids visible through the ContentInitBootstrap ServiceLoader."
  []
  (vec (ContentInitBootstraps/availableContentIds)))

(defn register-all-content!
  "Best-effort registration of every content module discovered through ServiceLoader."
  []
  (try
    (let [registered (long (ContentInitBootstraps/registerAll))]
      (when (zero? registered)
        (println (str "[" modid/mod-id "] WARNING: no content bootstrap providers found"))))
    (catch Throwable t
      (log/warn "ContentInitBootstraps/registerAll() failed:" (ex-message t))
      (log/stacktrace "ContentInitBootstraps/registerAll()" t)))
  nil)
