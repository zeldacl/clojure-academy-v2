(ns cn.li.fabric1201.init
  "Fabric 1.20.1 initialization - sets version for multimethod dispatch"
  (:require [cn.li.mcbase.bootstrap.init-common :as init-common]
            [cn.li.platform.target :as target]
            [cn.li.mcmod.content :as content]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.fabric1201.integration.optional-integrations :as optional-integrations]
            [cn.li.fabric1201.integration.achievement-bridge :as achievement-bridge])
  (:import [cn.li.fabric1201.recipe ModRecipeTypes]
           [cn.li.mc1201.trigger ModTriggers]))

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
     (achievement-bridge/init!)
     (optional-integrations/init!)
     (content/register-all-content!)
     (lifecycle/run-content-init!))))
