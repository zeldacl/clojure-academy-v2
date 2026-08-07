(ns cn.li.fabric262.init
  "Fabric 26.2 initialization - sets version for multimethod dispatch"
  (:require [cn.li.mcbase.bootstrap.init-common :as init-common]
            [cn.li.platform.target :as target]
            [cn.li.mcmod.content :as content]
            [cn.li.mcmod.lifecycle :as lifecycle])
  (:import [cn.li.fabric262.recipe ModRecipeTypes]
           [cn.li.mc262.trigger ModTriggers]))

(defn set-version!
  "Set the Fabric version for multimethod dispatch."
  []
  (init-common/set-platform-version! (target/current-target-key!)))

(defn- assert-platform-ready!
  []
  (init-common/assert-platform-ready! (target/current-target-key!)))

(defn init-from-java
  "Called from Java ModInitializer to initialize Clojure environment."
  []
  (init-common/init-from-java!
   (target/current-target-key!)
   (fn []
     (ModRecipeTypes/register)
     (ModTriggers/init)
     (content/register-all-content!)
     (lifecycle/run-content-init!))))
