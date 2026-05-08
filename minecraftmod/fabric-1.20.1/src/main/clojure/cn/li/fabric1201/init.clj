(ns cn.li.fabric1201.init
  "Fabric 1.20.1 initialization - sets version for multimethod dispatch"
  (:require [cn.li.mc1201.bootstrap.init-common :as init-common]))

(defn set-version!
  "Set the Fabric version for multimethod dispatch."
  []
  (init-common/set-platform-version! :fabric-1.20.1))

(defn- assert-platform-ready!
  []
  (init-common/assert-platform-ready! :fabric-1.20.1))

(defn init-from-java
  "Called from Java ModInitializer to initialize Clojure environment."
  []
  (init-common/init-from-java! :fabric-1.20.1 nil))
