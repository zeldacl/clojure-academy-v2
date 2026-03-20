(ns cn.li.mcmod.content
  "Helpers for triggering shared game content initialization without
   requiring Forge/Fabric modules to reference `cn.li.ac.*` directly."
  )

(defn ensure-content-init-registered!
  "Best-effort load of the shared content module.

   ac/core is responsible for calling:
   - (mcmod.lifecycle/register-content-init! #'init)

   This function just makes sure that happens before platforms call
   `mcmod.lifecycle/run-content-init!`."
  []
  (try
    ;; Trigger namespace load so it can register lifecycle init.
    (requiring-resolve 'cn.li.ac.core/init)
    (catch Throwable _ nil))
  nil)

