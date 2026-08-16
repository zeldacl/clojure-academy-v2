(ns cn.li.ac.ability.registry.combat-catalog
  "Canonical player-facing metadata registry for Combat Core abilities.

   This registry has no callback/action contract. Executable behavior is owned
   by Combat Core; AC stores only metadata needed by learning, presets and UI."
  (:require [cn.li.ac.ability.registry.registry-core :as registry-core]))

(defn- validate-metadata!
  [{:keys [id category-id level controllable? pattern ctrl-id name-key description-key icon]
    :as spec}]
  (when-not (and (keyword? id) (keyword? category-id) (integer? level)
                 (boolean? controllable?) (keyword? pattern)
                 (keyword? ctrl-id) (string? name-key)
                 (string? description-key) (string? icon))
    (throw (ex-info "Invalid Combat Core skill metadata"
                    {:skill-id id :category-id category-id :level level
                     :controllable? controllable? :pattern pattern})))
  spec)

(def ^:private ops
  (registry-core/make-registry-ops
   [:registry :combat-catalog]
   {:label "combat-catalog"
    :validate! validate-metadata!
    :conflict-key-fn (fn [spec]
                       (select-keys spec [:id :category-id :level :ctrl-id :pattern]))}))

(defonce ^:private installed?* (atom false))

(defn register-skill! [spec]
  ((:register! ops) spec))

(defn register-skills! [specs]
  (doseq [spec specs] (register-skill! spec))
  (reset! installed?* true)
  true)

(defn installed? [] @installed?*)

(defn get-skill [skill-id]
  ((:get ops) skill-id))

(defn raw-skill [skill-id] (get-skill skill-id))
(defn raw-skills [] ((:get-all ops)))
(defn raw-skill-entries [] ((:snapshot ops)))
(defn freeze! [] ((:freeze! ops)))
(defn reset-for-test! ([] (reset-for-test! {}))
  ([snapshot]
   ((:reset-for-test! ops) snapshot)
   (reset! installed?* (boolean (seq snapshot)))))
