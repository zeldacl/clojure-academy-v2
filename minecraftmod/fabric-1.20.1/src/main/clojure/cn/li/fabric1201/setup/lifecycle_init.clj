(ns cn.li.fabric1201.setup.lifecycle-init
  "Fabric lifecycle coordinator extracted from mod entry.

  Keeps the loader entry thin and makes phase ordering explicit."
  (:require [cn.li.mc1201.lifecycle.orchestrator :as lifecycle-orchestrator]
            [cn.li.mc1201.lifecycle.phase-contract :as phase-contract]))

(defn init-lifecycle!
  [{:keys [init-platform!
           init-from-java!
           init-core!
           load-config!
           bind-gameplay-config!
           init-blockstate-properties!
           register-content!
           install-runtime!
           register-events!]}]
  (lifecycle-orchestrator/run-lifecycle!
    {:label "fabric-1.20.1"
     :phases [(phase-contract/phase :platform-init #(do (init-platform!) (init-from-java!)))
              (phase-contract/phase :runtime-activation "core init and config load/bind"
                                    #(do (init-core!) (load-config!) (bind-gameplay-config!)))
              (phase-contract/phase :resource-init "shared blockstate property init" init-blockstate-properties!)
              (phase-contract/phase :content-registration register-content!)
              (phase-contract/phase :common-setup "runtime adapter + gui setup" install-runtime!)
              (phase-contract/phase :mod-bus-setup "register fabric callbacks/events" register-events!)]})
  nil)
