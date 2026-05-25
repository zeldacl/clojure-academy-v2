(ns cn.li.ac.ability.server.service.learning-conditions
  "Condition checks for skill learning eligibility."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.ability.registry.skill :as skill]))

(defn check-level-condition
  "Returns true if player-level ≥ min-level."
  [player-level min-level]
  (>= player-level min-level))

(defn check-dep-condition
  "Returns true if the parent skill's exp in ability-data ≥ required-exp."
  [ability-data parent-skill-id required-exp]
  (>= (adata/get-skill-exp ability-data parent-skill-id) (double required-exp)))

(defn check-developer-type-condition
  "Returns true if provided developer-type satisfies skill's minimum requirement."
  [developer-type skill-id]
  (when-let [s (skill/get-skill skill-id)]
    (developer/gte? developer-type (:developer-type s))))

(defn- has-learned-skill-of-level?
  [ability-data target-level]
  (boolean
    (some (fn [sid]
            (= target-level (:level (skill/get-skill sid))))
          (:learned-skills ability-data))))

(defn- evaluate-condition
  [condition ability-data]
  (case (:type condition)
    :any-skill-level
    (let [required-level (int (or (:level condition) 0))
          pass? (has-learned-skill-of-level? ability-data required-level)]
      {:pass? pass?
       :failure (when-not pass?
                  {:type :any-skill-level
                   :required-level required-level})})
    {:pass? true :failure nil}))

(defn check-all-conditions
  "Run all conditions for skill-id against current state.

  Args:
    skill-id:         keyword
    ability-data:     AbilityData map
    player-level:     int
    developer-type:   keyword :portable | :normal | :advanced

  Returns: {:pass? bool :failures [condition-key...]}"
  [skill-id ability-data player-level developer-type]
  (let [s (skill/get-skill skill-id)]
    (when-not s
      (throw (ex-info "Unknown skill" {:skill-id skill-id})))
    (let [base-failures (cond-> []
                          (not (check-level-condition player-level (:level s)))
                          (conj {:type :level :required (:level s) :actual player-level})
                          (not (developer/gte? developer-type (:developer-type s)))
                          (conj {:type :developer-type
                                 :required (:developer-type s)
                                 :actual developer-type}))
          prereq-failures (keep (fn [{:keys [skill-id min-exp]}]
                                  (when-not (check-dep-condition ability-data skill-id min-exp)
                                    {:type :prerequisite :skill-id skill-id :required min-exp
                                     :actual (adata/get-skill-exp ability-data skill-id)}))
                                (:prerequisites s))
          extra-failures (keep (fn [condition]
                                 (:failure (evaluate-condition condition ability-data)))
                               (:conditions s))
          failures (vec (concat base-failures prereq-failures extra-failures))]
      {:pass? (empty? failures)
       :failures failures})))

(defn can-learn?
  "Convenience wrapper – returns boolean."
  [skill-id ability-data player-level developer-type]
  (:pass? (check-all-conditions skill-id ability-data player-level developer-type)))
