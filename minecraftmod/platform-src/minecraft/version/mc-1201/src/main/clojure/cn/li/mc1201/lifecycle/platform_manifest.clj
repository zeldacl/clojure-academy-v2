(ns cn.li.mc1201.lifecycle.platform-manifest
  "Shared lifecycle builder for platform loaders.

  Loader components own their concrete phase manifests. This namespace only
  validates and turns a manifest plus action map into an executable lifecycle."
  (:require [cn.li.mc1201.lifecycle.phase-contract :as phase-contract]))

(defn- action-fn
  [label phase-id action-key action-map]
  (let [action (get action-map action-key)]
    (when-not (fn? action)
      (throw (ex-info "Missing lifecycle phase action"
                      {:target label
                       :phase phase-id
                       :action action-key
                       :available-actions (sort (keys action-map))})))
    action))

(defn- run-actions!
  [label phase-id action-keys action-map]
  (doseq [action-key action-keys]
    ((action-fn label phase-id action-key action-map))))

(defn build-lifecycle
  "Build a lifecycle-orchestrator spec from a loader-owned manifest and action-map."
  [{:keys [label phases] :as manifest} action-map]
  (when-not (seq phases)
    (throw (ex-info "Lifecycle manifest requires at least one phase"
                    {:manifest manifest})))
    {:label label
     :phases (mapv (fn [{:keys [id desc actions]}]
                     (phase-contract/phase
                      id
                      desc
                      #(run-actions! label id actions action-map)))
                   phases)})
