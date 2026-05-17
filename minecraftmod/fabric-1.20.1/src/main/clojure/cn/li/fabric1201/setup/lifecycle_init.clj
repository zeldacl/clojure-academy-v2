(ns cn.li.fabric1201.setup.lifecycle-init
  "Fabric lifecycle coordinator extracted from mod entry.

  Keeps the loader entry thin and makes phase ordering explicit."
  (:require [cn.li.mc1201.lifecycle.orchestrator :as lifecycle-orchestrator]
            [cn.li.mc1201.lifecycle.platform-manifest :as platform-manifest]))

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
   (platform-manifest/build-lifecycle
    :fabric-1.20.1
    {:init-platform! init-platform!
     :init-from-java! init-from-java!
     :init-core! init-core!
     :load-config! load-config!
     :bind-gameplay-config! bind-gameplay-config!
     :init-blockstate-properties! init-blockstate-properties!
     :register-content! register-content!
     :install-runtime! install-runtime!
     :register-events! register-events!}))
  nil)
