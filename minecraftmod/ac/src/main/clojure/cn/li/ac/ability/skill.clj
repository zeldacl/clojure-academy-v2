(ns cn.li.ac.ability.skill
  "Skills belong to a category and represent learnable abilities.

  Schema per skill:
    {:id                  keyword
     :category-id         keyword
     :name-key            string
     :description-key     string
     :icon                string       ; resource path
     :level               int          ; required player level (1-5)
     :controllable?       bool         ; whether it can be bound to a key
     :ctrl-id             keyword      ; unique within category
     :prerequisites       [{:skill-id k :min-exp float}]
     :developer-type      keyword      ; :portable :normal :advanced
     :damage-scale        float
     :cp-consume-speed    float
     :overload-consume-speed float
     :exp-incr-speed      float
      :destroy-blocks?     bool
      :enabled             bool
      :conditions          []           ; list of IDevCondition-equivalent fn maps}"
  (:require [cn.li.ac.ability.category :as cat]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Registry
;; ============================================================================

(defonce skill-registry (atom {}))

;; ============================================================================
;; Developer-type mapping (mirrors old Java enum)
;; ============================================================================

(defn min-developer-type
  "Returns the minimum developer type required for a given level."
  [level]
  (cond
    (<= level 2) :portable
    (= level 3)  :normal
    :else        :advanced))

(def developer-type-order [:portable :normal :advanced])

(def ^:private developer-type-rank
  {:portable 0
   :normal 1
   :advanced 2})

(defn developer-type-gte?
  "True when developer-type a is at least as powerful as b."
  [a b]
  (>= (long (get developer-type-rank a -1))
      (long (get developer-type-rank b -1))))

;; ============================================================================
;; Learning cost
;; formula: 3 + level^2 × 0.5  (matches original getLearningStims)
;; ============================================================================

(defn learning-cost [level]
  (+ 3.0 (* level level 0.5)))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-skill!
  "Register a skill spec. Merges defaults before storing."
  [{:keys [id category-id level] :as spec}]
  {:pre [(keyword? id) (keyword? category-id) (integer? level)]}
  (let [defaults {:controllable?          true
                  :damage-scale           1.0
                  :cp-consume-speed       1.0
                  :overload-consume-speed 1.0
                  :cooldown-ticks         nil
                  :exp-incr-speed         1.0
                  :destroy-blocks?        true
                  :enabled                true
                  :prerequisites          []
                  :conditions             []
                  :developer-type         (min-developer-type level)}
        full     (merge defaults spec)]
    (swap! skill-registry assoc id full)
    (log/info "Registered skill" id "in category" category-id)
    full))

;; ============================================================================
;; Query
;; ============================================================================

(defn get-skill [skill-id]
  (get @skill-registry skill-id))

(defn get-skills-for-category [cat-id]
  (filter #(= (:category-id %) cat-id) (vals @skill-registry)))

(defn get-controllable-skills-for-category
  "Returns skills that are controllable (can be key-bound), filtered by category."
  [cat-id]
  (filter #(and (= (:category-id %) cat-id) (:controllable? %))
          (vals @skill-registry)))

(defn can-control?
  "True when the skill is enabled and controllable."
  [skill-id]
  (when-let [s (get-skill skill-id)]
    (and (:enabled s) (:controllable? s))))

(defn get-skill-full-id
  "Returns string 'cat-id/skill-id' for display/logging."
  [skill-id]
  (when-let [s (get-skill skill-id)]
    (str (name (:category-id s)) "/" (name skill-id))))

(defn get-skill-icon-path
  "Returns icon resource path for a skill.
  Falls back to :icon field on skill spec."
  [skill-id]
  (get-in @skill-registry [skill-id :icon] ""))

(defn controllable-key
  "Returns [cat-id ctrl-id] pair for key-binding serialization."
  [skill-id]
  (when-let [s (get-skill skill-id)]
    [(:category-id s) (or (:ctrl-id s) skill-id)]))

(defn get-skill-by-controllable
  "Resolve skill-id by controllable pair [category-id ctrl-id]."
  [category-id ctrl-id]
  (some (fn [[sid s]]
          (when (and (= (:category-id s) category-id)
                     (= (or (:ctrl-id s) sid) ctrl-id))
            sid))
        @skill-registry))
