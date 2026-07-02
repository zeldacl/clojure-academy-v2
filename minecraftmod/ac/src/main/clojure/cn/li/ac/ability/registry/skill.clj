(ns cn.li.ac.ability.registry.skill
  "Canonical AC skill registry storage and registration API.

  Registry stored in Framework [:registry :skills]."
  (:require [cn.li.ac.ability.registry.skill-spec :as skill-spec]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; Skill Registry — Framework [:registry :skills]

(def ^:private skill-path [:registry :skills])

(defn- skill-registry-state-snapshot []
  (if-let [fw-atom fw/*framework*]
    (get-in @fw-atom skill-path {:registry {} :frozen? false})
    {:registry {} :frozen? false}))

(defn- update-skill-registry-state! [f & args]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom update-in skill-path
           (fn [current] (apply f (or current {:registry {} :frozen? false}) args))))
  nil)

(defn- stable-skill-identity [spec]
  (select-keys spec [:id :category-id :level :ctrl-id :pattern]))

(defn- assert-registry-open! []
  (when (:frozen? (skill-registry-state-snapshot))
    (throw (ex-info "Skill registry is frozen" {}))))

;; Backward-compatible factory
(defn create-skill-registry-runtime
  ([]
   {::skill-registry-runtime true
    :state* (atom {:registry {} :frozen? false})})
  ([{:keys [state*] :or {state* (atom {:registry {} :frozen? false})}}]
   {::skill-registry-runtime true :state* state*}))

;; Backward-compatible install
(defn install-skill-registry-runtime! [runtime]
  (when-let [fw-atom fw/*framework*]
    (when-let [state* (:state* runtime)]
      (swap! fw-atom assoc-in skill-path @state*)))
  runtime)

;; Query
(defn skill-registry-snapshot []
  (:registry (skill-registry-state-snapshot)))

(defn reset-skill-registry-for-test!
  ([]
   (reset-skill-registry-for-test! {}))
  ([snapshot]
   (when-let [fw-atom fw/*framework*]
     (swap! fw-atom assoc-in skill-path {:registry (or snapshot {}) :frozen? false}))
   nil))

(defn freeze-skill-registry! []
  (update-skill-registry-state! assoc :frozen? true)
  nil)

(defn register-skill!
  "Validate, normalize, and register a skill spec."
  [{:keys [id category-id level] :as spec}]
  (when-not (and (keyword? id) (keyword? category-id) (integer? level))
    (throw (IllegalArgumentException. "register-skill!: id & category-id must be keywords, level must be integer")))
  (let [full (skill-spec/normalize-skill-spec spec)]
    (if-let [existing (get (:registry (skill-registry-state-snapshot)) id)]
      (if (= (stable-skill-identity existing) (stable-skill-identity full))
        existing
        (throw (ex-info "Conflicting skill id"
                        {:id id :existing (stable-skill-identity existing)
                         :new (stable-skill-identity full)})))
      (do
        (assert-registry-open!)
        (update-skill-registry-state! assoc-in [:registry id] full)
        (log/info "Registered skill" id "in category" category-id)
        full))))

(defn raw-skill [skill-id]
  (get (:registry (skill-registry-state-snapshot)) skill-id))

(defn raw-skills []
  (vals (:registry (skill-registry-state-snapshot))))

(defn raw-skill-entries []
  (:registry (skill-registry-state-snapshot)))

(defn get-skill [skill-id]
  (some-> (raw-skill skill-id)
          skill-config/apply-skill-overrides))
