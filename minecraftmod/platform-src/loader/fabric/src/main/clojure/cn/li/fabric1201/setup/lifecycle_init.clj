(ns cn.li.fabric1201.setup.lifecycle-init
  "Fabric lifecycle coordinator extracted from mod entry.

  Keeps the loader entry thin and makes phase ordering explicit."
  (:require [cn.li.mc1201.lifecycle.orchestrator :as lifecycle-orchestrator]
            [cn.li.mc1201.lifecycle.platform-manifest :as platform-manifest]
            [cn.li.platform.target :as target]))

(defn- lifecycle-manifest []
  {:label (:id (target/current-target!))
   :phases [{:id :platform-init
             :actions [:init-platform! :init-from-java!]}
            {:id :runtime-activation
             :desc "load platform config and activate runtime content"
             :actions [:load-config! :activate-runtime-content!]}
            {:id :resource-init
             :desc "shared blockstate property init"
             :actions [:init-blockstate-properties!]}
            {:id :content-registration
             :actions [:register-content!]}
            {:id :common-setup
             :desc "runtime adapter + gui setup"
             :actions [:install-runtime!]}
            {:id :mod-bus-setup
             :desc "register fabric callbacks/events"
             :actions [:register-events!]}]})

(defn init-lifecycle!
  [{:keys [init-platform!
           init-from-java!
           load-config!
           activate-runtime-content!
           init-blockstate-properties!
           register-content!
           install-runtime!
           register-events!]}]
  (lifecycle-orchestrator/run-lifecycle!
   (platform-manifest/build-lifecycle
    (lifecycle-manifest)
    {:init-platform! init-platform!
     :init-from-java! init-from-java!
     :load-config! load-config!
      :activate-runtime-content! activate-runtime-content!
     :init-blockstate-properties! init-blockstate-properties!
     :register-content! register-content!
     :install-runtime! install-runtime!
     :register-events! register-events!}))
  nil)
