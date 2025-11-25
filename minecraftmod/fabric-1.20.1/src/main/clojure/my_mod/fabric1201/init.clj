(ns my-mod.fabric1201.init
  "Fabric 1.20.1 initialization - sets version for multimethod dispatch"
  (:require [my-mod.util.log :as log]))

(defn set-version! []
  "Set the Fabric version for multimethod dispatch"
  (alter-var-root #'my-mod.registry/*forge-version* (constantly :fabric-1.20.1))
  (alter-var-root #'my-mod.gui.api/*forge-version* (constantly :fabric-1.20.1))
  (log/info "Set version to :fabric-1.20.1"))

(defn init-from-java []
  "Called from Java ModInitializer to initialize Clojure environment"
  (log/info "Initializing Fabric 1.20.1 adapter...")
  (set-version!)
  (log/info "Fabric 1.20.1 adapter initialized"))
