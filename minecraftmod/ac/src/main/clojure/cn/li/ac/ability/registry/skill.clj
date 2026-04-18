(ns cn.li.ac.ability.registry.skill
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
      ;; cn.li.ac.ability.server.dispatch (pattern interpreter) instead of hand-written
      ;; :on-key-* functions per skill.
      ;;
      ;; :pattern:
      ;;   :instant | :hold-charge-release | :hold-channel | :toggle
      ;;   | :release-cast | :charge-window
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
                           :charge-window}
        required-action-keys {:instant #{:perform!}
                              :hold-charge-release #{:perform!}
                              :hold-channel #{}
                              :toggle #{:activate! :deactivate!}
                              :release-cast #{}
                              :charge-window #{}}
        allowed-action-keys #{:perform! :down! :tick! :up! :abort! :cost-fail! :activate! :deactivate!}
        allowed-fx-keys #{:start :update :perform :end}
        allowed-cost-stages #{:down :tick :up}
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
                  :cooldown {:mode :default}
                  :targeting {}
                  :transitions {}
                  :exp-policy {}
                  :cooldown-policy {}
                  :state {}
                  :ops {}
                  :perform []
                  :aim {}
                  :exp {}}
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
        _ (when (and (contains? spec :actions) (not (map? (:actions spec))))
            (throw (ex-info "Skill :actions must be a map"
                            {:skill-id id})))
        _ (when-not (or (vector? (:perform spec)) (nil? (:perform spec)))
            (throw (ex-info "Skill :perform must be a vector or nil"
                            {:skill-id id :value (:perform spec)})))
        _ (when-not (or (map? (:ops spec)) (nil? (:ops spec)))
            (throw (ex-info "Skill :ops must be a map or nil"
                            {:skill-id id :value (:ops spec)})))
        _ (when-not (or (map? (:aim spec)) (nil? (:aim spec)))
            (throw (ex-info "Skill :aim must be a map or nil"
                            {:skill-id id :value (:aim spec)})))
        _ (when-not (or (map? (:exp spec)) (nil? (:exp spec)))
            (throw (ex-info "Skill :exp must be a map or nil"
                            {:skill-id id :value (:exp spec)})))
        _ (doseq [[k _v] (:actions spec)]
            (when-not (contains? allowed-action-keys k)
              (throw (ex-info "Unsupported action key in :actions"
                              {:skill-id id :action-key k}))))
        ;; Check required actions for pattern, but allow ops vectors as alternatives.
        ;; run-stage! routes :perform → :perform vector, :down → :on-down, etc.
        _ (let [actions-preview (merge {} (:actions defaults) (:actions spec))
                has-ops-for?   (fn [action-key]
                                 (case action-key
                                   :perform! (seq (:perform spec))
                                   :down!    (or (seq (:on-down spec))  (seq (get-in spec [:ops :down])))
                                   :tick!    (or (seq (:on-tick spec))  (seq (get-in spec [:ops :tick])))
                                   :up!      (or (seq (:on-up spec))   (seq (get-in spec [:ops :up])))
                                   false))
                required (get required-action-keys (:pattern spec) #{})
                missing  (seq (remove #(or (contains? actions-preview %)
                                           (has-ops-for? %))
                                      required))]
            (when missing
              (throw (ex-info "Missing required actions for pattern"
                              {:skill-id id :pattern (:pattern spec) :missing missing}))))
        _ (when (and (contains? spec :cooldown) (not (map? (:cooldown spec))))
            (throw (ex-info "Skill :cooldown is required and must be a map"
                            {:skill-id id})))
        _ (when-not (contains? #{:default :manual}
                               (or (get-in spec [:cooldown :mode]) :default))
            (throw (ex-info "Skill :cooldown :mode must be :default or :manual"
                            {:skill-id id :cooldown (:cooldown spec)})))
        _ (doseq [k [:targeting :transitions :exp-policy :cooldown-policy :state]]
            (when (and (contains? spec k) (not (map? (get spec k))))
              (throw (ex-info "Skill policy fields must be maps"
                              {:skill-id id :field k :value (get spec k)}))))
        _ (doseq [[stage stage-spec] (:cost spec)]
            (when-not (contains? allowed-cost-stages stage)
              (throw (ex-info "Unsupported cost stage"
                              {:skill-id id :stage stage})))
            (when-not (map? stage-spec)
              (throw (ex-info "Cost stage spec must be a map"
                              {:skill-id id :stage stage :value stage-spec})))
            (when-not (contains? #{nil :runtime-speed} (:mode stage-spec))
              (throw (ex-info "Cost stage :mode must be :runtime-speed or omitted"
                              {:skill-id id :stage stage :value (:mode stage-spec)}))))
        _ (doseq [[fx-k fx-v] (:fx spec)]
            (when-not (contains? allowed-fx-keys fx-k)
              (throw (ex-info "Unsupported fx key"
                              {:skill-id id :fx-key fx-k})))
            (when-not (map? fx-v)
              (throw (ex-info "FX entry must be a map"
                              {:skill-id id :fx-key fx-k :value fx-v})))
            (when-not (keyword? (:topic fx-v))
              (throw (ex-info "FX entry requires keyword :topic"
                              {:skill-id id :fx-key fx-k :value fx-v}))))
        resolve-fn-ref (fn [v]
                         (cond
                           (fn? v) v
                           (var? v) (var-get v)
                           :else v))
        normalize-op (fn [op]
                       (when-not (and (vector? op)
                                      (= 2 (count op))
                                      (keyword? (first op))
                                      (map? (second op)))
                         (throw (ex-info "Each effect op must be [keyword map]"
                                         {:skill-id id :op op})))
                       op)
        merged (merge defaults spec)
        actions* (into {}
                       (map (fn [[k v]] [k (resolve-fn-ref v)]))
                       (or (:actions merged) {}))
        ops* (into {}
                   (map (fn [[stage ops]]
                          [stage (mapv normalize-op (or ops []))]))
                   (or (:ops merged) {}))
        perform* (mapv normalize-op (or (:perform merged) []))
        fx* (into {}
                  (map (fn [[k evt]]
                         [k (if (map? evt)
                              (update evt :payload resolve-fn-ref)
                              evt)]))
                  (or (:fx merged) {}))
        full (-> merged
                 (assoc :actions actions*)
                 (assoc :ops ops*)
                 (assoc :perform perform*)
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
