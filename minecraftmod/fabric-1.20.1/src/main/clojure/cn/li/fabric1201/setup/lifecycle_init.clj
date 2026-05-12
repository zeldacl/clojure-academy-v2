(ns cn.li.fabric1201.setup.lifecycle-init
  "Fabric lifecycle coordinator extracted from mod entry.

  Keeps the loader entry thin and makes phase ordering explicit."
  (:require [cn.li.mc1201.lifecycle.orchestrator :as lifecycle-orchestrator]))

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
     :phases [{:id :platform-init :desc "platform bootstrap + init-from-java" :fn #(do (init-platform!) (init-from-java!))}
              {:id :core-config :desc "core init and config load/bind" :fn #(do (init-core!) (load-config!) (bind-gameplay-config!))}
              {:id :resource-init :desc "shared blockstate property init" :fn init-blockstate-properties!}
              {:id :content-registration :desc "register content" :fn register-content!}
              {:id :runtime-setup :desc "runtime adapter + gui setup" :fn install-runtime!}
              {:id :event-wiring :desc "register fabric events" :fn register-events!}]})
  nil)
