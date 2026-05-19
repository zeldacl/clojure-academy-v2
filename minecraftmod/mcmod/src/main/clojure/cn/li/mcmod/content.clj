(ns cn.li.mcmod.content
  "Helpers for triggering shared game content initialization via content SPI."
  (:require [clojure.string :as str])
  (:import [cn.li.mcmod.content.spi ContentInitBootstraps ClojureNamespaceBootstrapInvoker]))

(defn- content-core-namespace
  "Return the conventional Clojure entry namespace for a content id.

  The ServiceLoader provider is still the primary path. This convention is a
  fallback for dev/runtime launchers whose classloader can see Clojure source
  roots but not META-INF/services providers from runtime source-set outputs."
  [content-id]
  (let [safe-id (-> (str content-id)
                    (str/replace #"[^A-Za-z0-9_.-]" "")
                    (str/replace #"-" "_"))]
    (when-not (str/blank? safe-id)
      (str "cn.li." safe-id ".core"))))

(defn- require-content-core!
  [content-id]
  (when-let [ns-name (content-core-namespace content-id)]
    (ClojureNamespaceBootstrapInvoker/requireNamespace ns-name)
    true))

(defn register-content!
  "Best-effort registration of a shared content module through ServiceLoader SPI.

  Content modules provide a ContentInitBootstrap implementation that registers
  lifecycle hooks into mcmod when discovered. The content id is supplied by the
  platform/datagen caller so mcmod stays content-agnostic."
  [content-id]
  (try
    (when-not (or (ContentInitBootstraps/register (str content-id))
                  (require-content-core! content-id))
      (println (str "[my_mod] WARNING: no content bootstrap found for " content-id)))
    (catch Throwable t
      (println (str "[my_mod] WARNING: ContentInitBootstraps/register(" content-id ") failed:")
               (ex-message t))
      nil))
  nil)

