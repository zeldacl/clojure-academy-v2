(ns cn.li.forge1201.init
  "Forge 1.20.1 initialization and version-specific implementations"
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.util.log :as log]))

(defn set-version!
  "Set the forge version for multimethod dispatch"
  []
  (alter-var-root #'cn.li.mcmod.platform.dispatch/*platform-version*
                  (constantly :forge-1.20.1))
  (log/info "Set platform dispatch to :forge-1.20.1"))

(defn init-from-java
  "Called from Java @Mod constructor - sets up version dispatch"
  []
  (log/info "Initializing Forge 1.20.1 adapter")
  (set-version!)
  ;; Ensure shared game logic namespace is loaded so it can register
  ;; `mcmod.lifecycle/register-content-init!` before we run it.
  (requiring-resolve 'cn.li.ac.core/init)
  (lifecycle/run-content-init!))
