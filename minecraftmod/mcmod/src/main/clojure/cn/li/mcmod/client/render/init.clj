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
  "Load core renderer namespaces to trigger auto-registration.

   Kept inside `mcmod` so Forge source code doesn't reference `cn.li.ac.*`
   directly."
  []
  ;; Simply require the namespaces - they auto-register via requiring-resolve
  (requiring-resolve 'cn.li.ac.block.wireless-matrix.render/register!)
  (requiring-resolve 'cn.li.ac.block.solar-gen.render/register!)
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

