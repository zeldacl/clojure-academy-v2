(ns cn.li.mc1201.lifecycle.platform-manifest
  "Shared lifecycle phase manifests for platform loaders.

  Platform modules provide concrete action functions; this namespace owns the
  ordered phase/action declarations so shared startup shape is not duplicated."
  (:require [cn.li.mc1201.lifecycle.phase-contract :as phase-contract]))

(def ^:private platform-manifests
  {:forge-1.20.1
   {:label "forge-1.20.1"
    :phases [{:id :platform-init
              :actions [:init-platform!]}
             {:id :runtime-activation
              :desc "activate runtime content and gameplay config"
              :actions [:activate-runtime-content! :bind-gameplay-config!]}
             {:id :resource-init
              :actions [:init-resource-definitions!]}
             {:id :content-registration
              :actions [:register-content!]}
             {:id :mod-bus-setup
              :actions [:setup-mod-bus!]}
             {:id :common-setup
              :actions [:run-common-setup!]}]}

   :fabric-1.20.1
   {:label "fabric-1.20.1"
    :phases [{:id :platform-init
              :actions [:init-platform! :init-from-java!]}
             {:id :runtime-activation
              :desc "core init and config load/bind"
              :actions [:init-core! :load-config! :bind-gameplay-config!]}
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
              :actions [:register-events!]}]}})

(defn platform-manifest
  [platform-key]
  (or (get platform-manifests platform-key)
      (throw (ex-info "Unknown platform lifecycle manifest"
                      {:platform platform-key
                       :known-platforms (sort (keys platform-manifests))}))))

(defn- action-fn
  [platform-key phase-id action-key action-map]
  (let [action (get action-map action-key)]
    (when-not (fn? action)
      (throw (ex-info "Missing lifecycle phase action"
                      {:platform platform-key
                       :phase phase-id
                       :action action-key
                       :available-actions (sort (keys action-map))})))
    action))

(defn- run-actions!
  [platform-key phase-id action-keys action-map]
  (doseq [action-key action-keys]
    ((action-fn platform-key phase-id action-key action-map))))

(defn build-lifecycle
  "Build a lifecycle-orchestrator spec for platform-key from action-map."
  [platform-key action-map]
  (let [{:keys [label phases]} (platform-manifest platform-key)]
    {:label label
     :phases (mapv (fn [{:keys [id desc actions]}]
                     (phase-contract/phase
                      id
                      desc
                      #(run-actions! platform-key id actions action-map)))
                   phases)}))