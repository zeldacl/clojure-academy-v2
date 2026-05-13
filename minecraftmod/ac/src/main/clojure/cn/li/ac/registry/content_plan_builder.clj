(ns cn.li.ac.registry.content-plan-builder
	"Builder for AC content load phases.

	Phase specs can be registered incrementally and materialized into the
	ordered load plan consumed by `content-namespaces`."
	(:require [cn.li.mcmod.util.log :as log]))

(defonce ^:private registered-phase-plugins* (atom []))

(defn register-phase-plugin!
	[{:keys [phase] :as phase-spec}]
	(when-not phase
		(throw (ex-info "Content phase plugin requires :phase" {:phase-spec phase-spec})))
	(swap! registered-phase-plugins*
				 (fn [plugins]
					 (conj (vec (remove #(= phase (:phase %)) plugins)) phase-spec)))
	phase-spec)

(defn registered-phase-plugins
	[]
	@registered-phase-plugins*)

(defn build-load-plan
	[]
	(let [plan @registered-phase-plugins*]
		(log/debug "Built AC content load plan" {:phases (mapv :phase plan)})
		plan))