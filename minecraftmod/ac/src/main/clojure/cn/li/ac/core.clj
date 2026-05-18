(ns cn.li.ac.core
  (:require [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.ac.datagen.bootstrap :as datagen-bootstrap]
            [cn.li.ac.registry.hooks :as hooks]))

(defn- resolve-required
  [var-sym]
  (or (requiring-resolve var-sym)
      (throw (ex-info (str "Missing required var: " var-sym)
                      {:var var-sym}))))

(defn init
  []
  ((resolve-required 'cn.li.ac.core.init/init)))

(defn activate-runtime-content!
  []
  ((resolve-required 'cn.li.ac.core.content-loader/activate-runtime-content!)))

(defn register-datagen-metadata!
  []
  (datagen-bootstrap/register-datagen-metadata!))

;; Phase1.4/Phase2: register content init hook for platform adapters.
(lifecycle/register-content-init! #'init)
(lifecycle/register-runtime-content-activation! #'activate-runtime-content!)
(lifecycle/register-datagen-metadata-init! #'register-datagen-metadata!)

;; Register client-side initialization callback
(defn- init-client-renderers
  "Load renderer namespaces to trigger auto-registration.
  Called by mcmod during client initialization."
  []
  (when-let [install-terminal-hooks!
             (requiring-resolve 'cn.li.ac.terminal.platform-bridge/install-terminal-ui-hooks!)]
    (install-terminal-hooks!))
  (hooks/load-all-client-renderers!))

;; Register the callback with mcmod lifecycle system
(when-let [register-fn (requiring-resolve 'cn.li.mcmod.lifecycle/register-client-init!)]
  (register-fn init-client-renderers))
