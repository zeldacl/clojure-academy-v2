(ns cn.li.forge1201.init
  "Forge 1.20.1 initialization and version-specific implementations"
  (:require [my-mod.core :as core]
            [my-mod.registry :as reg]
            [cn.li.mcmod.gui.api :as gui-api]
            [my-mod.util.log :as log]))

(defn set-version!
  "Set the forge version for multimethod dispatch"
  []
  (alter-var-root #'reg/*forge-version* (constantly :forge-1.20.1))
  (alter-var-root #'gui-api/*forge-version* (constantly :forge-1.20.1))
  (log/info "Set version dispatch to :forge-1.20.1"))

(defn init-from-java
  "Called from Java @Mod constructor - sets up version dispatch"
  []
  (log/info "Initializing Forge 1.20.1 adapter")
  (set-version!)
  (core/init))
