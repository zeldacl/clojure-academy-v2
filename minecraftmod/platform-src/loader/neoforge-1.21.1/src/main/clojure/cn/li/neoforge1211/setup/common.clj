(ns cn.li.neoforge1211.setup.common
  "Forge common-setup wiring extracted from mod entry.

  Keeps mod namespace focused on registration/bootstrap while this namespace owns
  common setup side effects and event subscriptions."
  (:require [cn.li.neoforge1211.gui.init :as gui-init]
            [cn.li.neoforge1211.registry.content-registration :as content-registration]
            [cn.li.neoforge1211.runtime.lifecycle :as runtime-lifecycle]
            [cn.li.neoforge1211.integration.forge-energy :as forge-energy]
            [cn.li.neoforge1211.integration.ic2-energy :as ic2-energy]
            [cn.li.neoforge1211.runtime.item-handler :as runtime-item-handler]
            [cn.li.neoforge1211.integration.tutorial-events :as tutorial-events]
            [cn.li.neoforge1211.integration.imc-dispatch :as imc-dispatch]
            [cn.li.neoforge1211.integration.events.world :as world-events]
            [cn.li.neoforge1211.setup.event-registration :as event-registration]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.neoforgebase.bootstrap ForgeBootstrapGuard]))

(defn run-common-setup!
  []
  (if-not (ForgeBootstrapGuard/markCommonSetupCompleteIfAbsent)
    (log/info "Forge common setup wiring already complete; skipping duplicate invocation")
    (do
      (content-registration/assert-scripted-blocks-bundled!)
      (gui-init/init-common!)
      (runtime-lifecycle/init-common!)
      (forge-energy/init-forge-energy!)
      (ic2-energy/init-ic2-energy!)
      (runtime-item-handler/init!)
      (tutorial-events/init!)
      (imc-dispatch/init!)
      (world-events/register-on-world-state-changed!)
      (event-registration/register-common-event-listeners!)
      (log/info "Forge common setup wiring complete"))))
