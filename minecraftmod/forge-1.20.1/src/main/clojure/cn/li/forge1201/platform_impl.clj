(ns cn.li.forge1201.platform-impl
  "Facade for Forge platform protocol installation.

  This namespace remains side-effect free during checkClojure loading.
  Real protocol extensions are installed by cn.li.forge1201.platform-impl-impl
  and loaded lazily when init-platform! is called."
  (:require [cn.li.mcmod.util.log :as log]))

(set! *warn-on-reflection* true)

(defonce ^:private init-fn* (atom nil))
(defonce ^:private impl-loader
  (delay
    (require 'cn.li.forge1201.platform-impl-impl)
    true))

(defn register-init!
  [f]
  (reset! init-fn* f)
  nil)

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations."
  []
  (when (nil? @init-fn*)
    @impl-loader)
  (if-let [f @init-fn*]
    (f)
    (do
      (log/error "Forge platform impl initializer was not registered")
      (throw (ex-info "Forge platform impl initializer missing" {})))))
