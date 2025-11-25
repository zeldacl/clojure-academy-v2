(ns my-mod.forge1165.init
  "Forge 1.16.5 initialization and version-specific implementations"
  (:require [my-mod.core :as core]
            [my-mod.registry :as reg]
            [my-mod.gui.api :as gui-api]
            [my-mod.util.log :as log]))

(defn set-version!
  "Set the forge version for multimethod dispatch"
  []
  (alter-var-root #'reg/*forge-version* (constantly :forge-1.16.5))
  (alter-var-root #'gui-api/*forge-version* (constantly :forge-1.16.5))
  (log/info "Set version dispatch to :forge-1.16.5"))

(defn init-from-java
  "Called from Java @Mod constructor - sets up version dispatch"
  []
  (log/info "Initializing Forge 1.16.5 adapter")
  (set-version!)
  (core/init))
