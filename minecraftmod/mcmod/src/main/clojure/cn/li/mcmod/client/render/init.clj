(ns cn.li.mcmod.client.render.init
  "CLIENT-ONLY: Renderer registration system.

  This namespace must be loaded via side-checked requiring-resolve from the
  platform layer. It provides platform-agnostic renderer registration that
  delegates to platform-specific implementations.

  Single entrypoint for registering all core renderers. Platform client init
  should call `register-all-renderers!` once; individual renderer namespaces
  keep their own `register!` implementations."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private registered? (atom false))

(def ^:private renderer-init-fns
  ;; Vector of (fn [] ...) or Var values implementing IFn.
  (atom []))

(defn register-renderer-init-fn!
  "Register a single renderer init callback. Called by renderer namespaces at load time."
  [f]
  (swap! renderer-init-fns conj f)
  nil)

(defn register-renderer-init-fns!
  "Register renderer init callbacks to be invoked by `register-all-renderers!`.
   Each callback should be a (fn [] ...) or a Var referencing such a function."
  [fns]
  (reset! renderer-init-fns (vec fns))
  nil)

(defn register-default-renderer-init-fns!
  "Trigger client-side initialization callbacks that load renderer namespaces.

  This function is called by platform layer during client setup.
  Content modules register their callbacks via lifecycle/register-client-init!."
  []
  (when-let [run-client-init! (requiring-resolve 'cn.li.mcmod.lifecycle/run-client-init!)]
    (run-client-init!))
  nil)

(defn register-all-renderers!
  "Require and register all renderers (idempotent)."
  []
  (when (compare-and-set! registered? false true)
    (log/info "Registering all core renderers...")
    (let [fns @renderer-init-fns]
      (when (empty? fns)
        (log/warn "No renderer init callbacks registered; skipping."))
      (doseq [f fns]
        (f)))

    (log/info "Core renderers registered.")))

