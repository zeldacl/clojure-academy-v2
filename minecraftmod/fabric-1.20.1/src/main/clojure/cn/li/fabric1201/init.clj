(ns cn.li.fabric1201.init
  "Fabric 1.20.1 initialization - sets version for multimethod dispatch"
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.util.log :as log]))

(defn set-version!
  "Set the Fabric version for multimethod dispatch."
  []
  (alter-var-root #'platform-dispatch/*platform-version*
                  (constantly :fabric-1.20.1))
  (log/info "Set platform dispatch to :fabric-1.20.1"))

(defn init-from-java
  "Called from Java ModInitializer to initialize Clojure environment."
  []
  (log/info "Initializing Fabric 1.20.1 adapter...")
  (set-version!)
  (log/info "Fabric 1.20.1 adapter initialized"))
