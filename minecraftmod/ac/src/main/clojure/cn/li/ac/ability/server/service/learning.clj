(ns cn.li.ac.ability.server.service.learning
  "All functions are pure (take data maps, return updated data maps + events).
  Side-effectful callers (player-state.clj) apply the returned data and fire events."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.registry.category :as cat]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Dev Condition Checks
;; ============================================================================

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
    (skill/developer-type-gte? developer-type (:developer-type s))))

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
    (let [failures (cond-> []
                     ;; Level condition
                     (not (check-level-condition player-level (:level s)))
                     (conj {:type :level :required (:level s) :actual player-level})

                     ;; Developer type
                     (not (skill/developer-type-gte? developer-type (:developer-type s)))
                     (conj {:type :developer-type
                            :required (:developer-type s)
                            :actual developer-type})

                     ;; Prerequisites
                     :always
                     (into (keep (fn [{:keys [skill-id min-exp]}]
                                   (when-not (check-dep-condition ability-data skill-id min-exp)
                                     {:type :prerequisite :skill-id skill-id :required min-exp
                                      :actual (adata/get-skill-exp ability-data skill-id)}))
                                 (:prerequisites s))))]
      {:pass? (empty? failures)
       :failures failures})))

;; ============================================================================
;; Learning
;; ============================================================================

(defn can-learn?
  "Convenience wrapper – returns boolean."
  [skill-id ability-data player-level developer-type]
  (:pass? (check-all-conditions skill-id ability-data player-level developer-type)))

(defn learn-skill
  "Learn a skill (unchecked – caller must verify conditions first).
  Returns {:data updated-ability-data :event skill-learn-event}."
  [ability-data uuid skill-id]
  (if (adata/is-learned? ability-data skill-id)
    {:data ability-data :event nil}
    {:data  (adata/learn-skill ability-data skill-id)
     :event (evt/make-skill-learn-event uuid skill-id)}))

;; ============================================================================
;; Experience & Level Progression
;; ============================================================================

(defn level-up-threshold
  "Number of exp points needed to advance to next level.
  Formula: controllable-skill-count × category-prog-rate × global-prog-rate"
  [cat-id]
  (let [ctrl-count  (count (skill/get-controllable-skills-for-category cat-id))
        cat-rate    (cat/get-prog-incr-rate cat-id)
        global-rate cfg/*prog-incr-rate*]
    (* ctrl-count cat-rate global-rate)))

(defn can-level-up?
  [ability-data]
  (let [level (:level ability-data)
        cat   (:category-id ability-data)]
    (and (< level 5)
         (some? cat)
         (>= (:level-progress ability-data)
             (level-up-threshold cat)))))

(defn add-skill-exp
  "Add exp to a skill. Also accumulates level progress.
  Returns {:data updated-ability-data :events [...]}.
  
  exp-incr-speed is the skill-specific multiplier (from skill spec)."
  [ability-data uuid skill-id raw-amount exp-incr-speed]
  (let [scaled-amount (* (double raw-amount)
                         (double (or exp-incr-speed 1.0))
                         cfg/*prog-incr-rate*)
        {:keys [data delta]} (adata/add-skill-exp ability-data skill-id scaled-amount)
        data2 (adata/add-level-progress data delta)
        exp-added-event {:event/type evt/EVT-SKILL-EXP-ADDED
                         :event/side :server
                         :uuid uuid :skill-id skill-id :amount delta}
        exp-changed-event {:event/type evt/EVT-SKILL-EXP-CHANGED
                           :event/side :server
                           :uuid uuid :skill-id skill-id
                           :new-exp (adata/get-skill-exp data2 skill-id)}]
    {:data   data2
     :events (if (pos? delta) [exp-added-event exp-changed-event] [])}))

(defn level-up
  "Execute level-up if conditions are met.
  Returns {:data updated-ability-data :event level-change-event | nil}."
  [ability-data uuid]
  (if-not (can-level-up? ability-data)
    {:data ability-data :event nil}
    (let [old-level (:level ability-data)
          new-level (inc old-level)
          data2     (adata/set-level ability-data new-level)]
      {:data  data2
       :event (evt/make-level-change-event uuid old-level new-level)})))
