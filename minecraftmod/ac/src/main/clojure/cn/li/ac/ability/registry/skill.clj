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
      ;;   | :release-cast | :charge-window | :passive
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
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.ac.ability.registry.developer-type :as dev-type]
            [cn.li.ac.ability.registry.learning-cost :as lc]
            [cn.li.ac.ability.registry.skill-schema :as schema]))

;; ============================================================================
;; Registry
;; ============================================================================

(defonce skill-registry (atom {}))

;; ============================================================================
;; Developer-type mapping — delegates to developer-type module
;; ============================================================================

(defn min-developer-type
  "Returns the minimum developer type required for a given level."
  [level]
  (dev-type/min-for-level level))

(def developer-type-order dev-type/order)

(defn developer-type-gte?
  "True when developer-type a is at least as powerful as b."
  [a b]
  (dev-type/gte? a b))

;; ============================================================================
;; Learning cost — delegates to learning-cost module
;; ============================================================================

(defn learning-cost [level]
  (lc/learning-cost level))

;; ============================================================================
;; Registration
;; ============================================================================

(defn- build-defaults
  [{:keys [level]}]
  {:controllable?          true
   :damage-scale           1.0
   :cp-consume-speed       1.0
   :overload-consume-speed 1.0
   :cooldown-ticks         nil
   :exp-incr-speed         1.0
   :destroy-blocks?        true
   :enabled                true
   :prerequisites          []
   :conditions             []
   :developer-type         (dev-type/min-for-level level)
   :cooldown               {:mode :default}
   :targeting {}
   :transitions {}
   :exp-policy {}
   :cooldown-policy {}
   :state {}
   :ops {}
   :perform []
   :aim {}
   :exp {}})

(defn- resolve-fn-ref [v]
  (cond
    (fn? v)  v
    (var? v) (var-get v)
    :else    v))

(defn- normalize-op [id op]
  (when-not (and (vector? op)
                 (= 2 (count op))
                 (keyword? (first op))
                 (map? (second op)))
    (throw (ex-info "Each effect op must be [keyword map]"
                    {:skill-id id :op op})))
  op)

(defn- normalize-merged
  "Resolve fn-refs and normalize ops/perform/fx vectors after merging defaults."
  [{:keys [id] :as merged}]
  (let [actions* (into {} (map (fn [[k v]] [k (resolve-fn-ref v)])) (or (:actions merged) {}))
        ops*     (into {} (map (fn [[stage ops]]
                                 [stage (mapv #(normalize-op id %) (or ops []))]))
                       (or (:ops merged) {}))
        perform* (mapv #(normalize-op id %) (or (:perform merged) []))
        fx*      (into {} (map (fn [[k evt]]
                                 [k (if (map? evt)
                                      (update evt :payload resolve-fn-ref)
                                      evt)]))
                       (or (:fx merged) {}))]
    (-> merged
        (assoc :actions actions*)
        (assoc :ops ops*)
        (assoc :perform perform*)
        (assoc :fx fx*))))

(defn register-skill!
  "Validate, merge defaults, normalize, and register a skill spec.
  Delegates validation to skill-schema/validate! (pure, testable).
  Returns the stored spec."
  [{:keys [id category-id level] :as spec}]
  {:pre [(keyword? id) (keyword? category-id) (integer? level)]}
  (schema/validate! spec)
  (let [full (-> (merge (build-defaults spec) spec)
                 normalize-merged)]
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

(defn get-controllable-skills-at-level
  "Returns controllable skills for a category at exactly the given level."
  [cat-id level]
  (filter #(and (= (:category-id %) cat-id)
                (:controllable? %)
                (= (:level %) level))
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
