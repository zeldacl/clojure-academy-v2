(ns cn.li.forge1201.init
  "Forge 1.20.1 initialization and version-specific implementations"
  (:require [cn.li.mc1201.bootstrap.init-common :as init-common]
            [cn.li.mcmod.platform.events :as platform-events]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.content :as content]
            [cn.li.forge1201.integration.imc-dispatch :as imc-dispatch]
            [cn.li.forge1201.achievement.bridge :as achievement-bridge])
  (:import [cn.li.forge1201.trigger ModTriggers]))

(defn set-version!
  "Set the forge version for multimethod dispatch"
  []
  (init-common/set-platform-version! :forge-1.20.1))

(defn- assert-platform-ready!
  []
  (init-common/assert-platform-ready! :forge-1.20.1))

(defn init-from-java
  "Called from Java @Mod constructor - sets up version dispatch"
  []
  (init-common/init-from-java!
    :forge-1.20.1
    (fn []
      ;; Bind platform-neutral event bridge to Forge IMC dispatcher.
      (alter-var-root #'platform-events/*fire-event-fn*
                      (constantly imc-dispatch/dispatch-event!))
      ;; Register custom advancement triggers.
      (ModTriggers/init)
      ;; Ensure AC content init is registered through the generic content SPI.
      (content/register-content! "ac")
      (lifecycle/run-content-init!)
      (achievement-bridge/init!))))
