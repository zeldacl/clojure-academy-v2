(ns cn.li.mcmod.client.render.init
  "Single entrypoint for registering all core renderers.

  Platform client init should call `register-all-renderers!` once; individual
  renderer namespaces keep their own `register!` implementations."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private registered? (atom false))

(def ^:private renderer-init-fns
  ;; Vector of (fn [] ...) or Var values implementing IFn.
  (atom []))

(defn register-renderer-init-fns!
  "Register renderer init callbacks to be invoked by `register-all-renderers!`.
   Each callback should be a (fn [] ...) or a Var referencing such a function."
  [fns]
  (reset! renderer-init-fns (vec fns))
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

