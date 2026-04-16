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
      :conditions          []           ; list of IDevCondition-equivalent fn maps

      ;; Runtime (new DSL, data-first)
      ;; When :pattern is present, server-side input callbacks can be provided by
      ;; cn.li.ac.ability.skill-runtime (pattern interpreter) instead of hand-written
      ;; :on-key-* functions per skill.
      ;;
      ;; :pattern:
      ;;   :instant | :hold-charge-release | :hold-channel | :toggle
      ;;   | :release-cast | :hold-target | :charge-window
      ;; :cooldown:
      ;;   {:mode :default}  -> context-runtime applies main cooldown on key-up
      ;;   {:mode :manual}   -> skill handles cooldown itself
      ;; :cost:
      ;;   {:tick {:cp ... :overload ...}}
      ;;   or runtime-scaled:
      ;;   {:tick {:mode :runtime-speed :cp-speed 1.0 :overload-speed 1.0}}
      ;; :actions:
      ;;   map of small functions implementing the skill-specific parts
      ;; :fx:
      ;;   data describing server->client context messages (start/update/perform/end)
      :pattern             keyword | nil
      :cooldown            map | nil
      :cost                map | nil
      :actions             map | nil
      :fx                  map | nil}"
  (:require [cn.li.mcmod.util.log :as log]))

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
(let [supported-patterns #{:instant
                            :hold-charge-release
                            :hold-channel
                            :toggle
                            :release-cast
                            :hold-target
                            :charge-window}
        defaults {:controllable? true
                  :damage-scale 1.0
                  :cp-consume-speed 1.0
                  :overload-consume-speed 1.0
                  :cooldown-ticks nil
                  :exp-incr-speed 1.0
                  :destroy-blocks? true
                  :enabled true
                  :prerequisites []
                  :conditions []
                  :developer-type (min-developer-type level)
                  :cooldown {:mode :default}}
        ;; Runtime contract is spec-first. Legacy :on-key-* callback keys are
        ;; intentionally removed and rejected so that all lifecycle behavior goes
        ;; through :pattern + :actions.
        _ (doseq [k [:on-key-down :on-key-tick :on-key-up :on-key-abort]]
            (when (contains? spec k)
              (throw (ex-info "Legacy callback keys are no longer supported for skills"
                              {:skill-id id :legacy-key k}))))
        _ (when (and (contains? spec :cooldown)
                     (not (map? (:cooldown spec))))
            (throw (ex-info "Skill :cooldown must be a map"
                            {:skill-id id :value (:cooldown spec)})))
        _ (when-let [p (:pattern spec)]
            (when-not (contains? supported-patterns p)
              (throw (ex-info "Unknown skill :pattern"
                              {:skill-id id :pattern p}))))
        _ (when-not (keyword? (:pattern spec))
            (throw (ex-info "Skill :pattern is required"
                            {:skill-id id})))
        _ (when-not (map? (:actions spec))
            (throw (ex-info "Skill :actions must be a map"
                            {:skill-id id})))
        _ (when-not (map? (:cooldown spec))
            (throw (ex-info "Skill :cooldown is required and must be a map"
                            {:skill-id id})))
        _ (when-not (contains? #{:default :manual} (get-in spec [:cooldown :mode]))
            (throw (ex-info "Skill :cooldown :mode must be :default or :manual"
                            {:skill-id id :cooldown (:cooldown spec)})))
        resolve-fn-ref (fn [v]
                         (cond
                           (fn? v) v
                           (var? v) (var-get v)
                           (symbol? v)
                           (try
                             (var-get (requiring-resolve v))
                             (catch Exception e
                               (log/warn "Failed to resolve skill fn ref" id v (ex-message e))
                               nil))
                           :else v))
        merged (merge defaults spec)
        actions* (into {}
                       (map (fn [[k v]] [k (resolve-fn-ref v)]))
                       (or (:actions merged) {}))
        fx* (into {}
                  (map (fn [[k evt]]
                         [k (if (map? evt)
                              (update evt :payload resolve-fn-ref)
                              evt)]))
                  (or (:fx merged) {}))
        full (-> merged
                 (assoc :actions actions*)
                 (assoc :fx fx*))]
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
