(ns cn.li.ac.core
  (:require [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.ac.datagen.bootstrap :as datagen-bootstrap]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.testing.smoke-manifest :as smoke-manifest]))

(defonce ^:private lifecycle-hooks-registered?
  (atom false))

(defn lifecycle-hooks-guard-snapshot
  []
  @lifecycle-hooks-registered?)

(defn reset-lifecycle-hooks-guard-for-test!
  ([]
   (reset-lifecycle-hooks-guard-for-test! false))
  ([registered?]
   (reset! lifecycle-hooks-registered? (boolean registered?))
   nil))

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

;; Register client-side initialization callback
(defn- init-client-renderers
  "Run content-owned client renderer initialization.
  Called by mcmod during client initialization."
  []
  (when-let [install-terminal-hooks!
             (requiring-resolve 'cn.li.ac.terminal.platform-bridge/install-terminal-ui-hooks!)]
    (install-terminal-hooks!))
  (hooks/load-all-client-renderers!))

(defn register-lifecycle-hooks!
  "Register AC lifecycle hooks with mcmod.

  This is the explicit bootstrap entrypoint used by ServiceLoader and fallback
  content discovery. Requiring this namespace alone must not mutate lifecycle
  state."
  []
  (when (compare-and-set! lifecycle-hooks-registered? false true)
    (smoke-manifest/register!)
    (lifecycle/register-content-init! #'init)
    (lifecycle/register-runtime-content-activation! #'activate-runtime-content!)
    (lifecycle/register-datagen-metadata-init! #'register-datagen-metadata!)
    (when-let [register-fn (requiring-resolve 'cn.li.mcmod.lifecycle/register-client-init!)]
      (register-fn init-client-renderers)))
  nil)
