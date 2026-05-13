(ns cn.li.ac.core
  (:require [cn.li.ac.core.content-loader :as content-loader]
            [cn.li.ac.core.init :as core-init]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.ac.registry.hooks :as hooks]))

(def init core-init/init)
(def activate-runtime-content! content-loader/activate-runtime-content!)

;; Phase1.4/Phase2: register content init hook for platform adapters.
(lifecycle/register-content-init! #'init)
(lifecycle/register-runtime-content-activation! #'activate-runtime-content!)

;; Register client-side initialization callback
(defn- init-client-renderers
  "Load renderer namespaces to trigger auto-registration.
  Called by mcmod during client initialization."
  []
  (hooks/load-all-client-renderers!))

;; Register the callback with mcmod lifecycle system
(when-let [register-fn (requiring-resolve 'cn.li.mcmod.lifecycle/register-client-init!)]
  (register-fn init-client-renderers))
