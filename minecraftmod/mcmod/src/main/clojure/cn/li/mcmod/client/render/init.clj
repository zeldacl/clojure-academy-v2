(ns my-mod.client.render.init
  "Single entrypoint for registering all core renderers.

  Platform client init should call `register-all-renderers!` once; individual
  renderer namespaces keep their own `register!` implementations."
  (:require [my-mod.util.log :as log]))

(defonce ^:private registered? (atom false))

(defn register-all-renderers!
  "Require and register all renderers (idempotent)."
  []
  (when (compare-and-set! registered? false true)
    (log/info "Registering all core renderers...")
    ;; Require renderers lazily to avoid client-only ns load on server.
    (require
      'my-mod.client.render.matrix-renderer
      'my-mod.client.render.solar-renderer)

    ((requiring-resolve 'my-mod.client.render.matrix-renderer/register!))
    ((requiring-resolve 'my-mod.client.render.solar-renderer/register!))

    (log/info "Core renderers registered.")))

