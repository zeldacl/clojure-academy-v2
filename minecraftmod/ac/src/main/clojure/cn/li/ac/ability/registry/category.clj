(ns cn.li.ac.ability.registry.category
  "Categories group skills into thematic sets (e.g., esper, radiation, mist).
  Each category definition is a plain map stored in category-registry.

  Schema per category:
    {:id            keyword        ; unique identifier, e.g. :esper
     :name-key      string         ; i18n key
      :icon          string         ; resource path to icon texture
      :color         [r g b a]      ; RGBA floats for UI tinting
      :prog-incr-rate float         ; experience gain rate for this category
      :enabled       bool}"
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Registry
;; ============================================================================

(defn default-category-registry-runtime-state
  []
  {:registry {}
   :frozen? false})

(defn create-category-registry-runtime
  ([]
   (create-category-registry-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-category-registry-runtime-state))}}]
   {::runtime ::category-registry-runtime
    :state* state*}))

(def ^:dynamic *category-registry-runtime* nil)

(defonce ^:private installed-category-registry-runtime
  (create-category-registry-runtime))

(defn- category-registry-runtime?
  [runtime]
  (and (map? runtime)
       (= ::category-registry-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-category-registry-runtime
  [runtime f]
  (when-not (category-registry-runtime? runtime)
    (throw (ex-info "Expected category registry runtime"
                    {:runtime runtime})))
  (binding [*category-registry-runtime* runtime]
    (f)))

(defmacro with-category-registry-runtime
  [runtime & body]
  `(call-with-category-registry-runtime ~runtime (fn [] ~@body)))

(defn- current-category-registry-runtime
  []
  (or *category-registry-runtime*
      installed-category-registry-runtime))

(defn- category-registry-state-atom
  []
  (:state* (current-category-registry-runtime)))

(defn- category-registry-state-snapshot
  []
  @(category-registry-state-atom))

(defn- update-category-registry-state!
  [f & args]
  (apply swap! (category-registry-state-atom) f args))

(defn- assert-registry-open!
  []
  (when (:frozen? (category-registry-state-snapshot))
    (throw (ex-info "Category registry is frozen" {}))))

(defn category-registry-snapshot
  []
  (:registry (category-registry-state-snapshot)))

(defn reset-category-registry-for-test!
  ([]
   (reset-category-registry-for-test! {}))
  ([snapshot]
    (reset! (category-registry-state-atom)
          {:registry (or snapshot {})
          :frozen? false})
   nil))

(defn freeze-category-registry!
  []
    (update-category-registry-state! assoc :frozen? true)
  nil)

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-category!
  "Register a category spec with idempotent duplicate handling."
  [{:keys [id] :as spec}]
  {:pre [(keyword? id) (string? (:name-key spec))]}
  (if-let [existing (get (:registry (category-registry-state-snapshot)) id)]
    (if (= existing spec)
      existing
      (throw (ex-info "Conflicting ability category id"
                      {:id id :existing existing :new spec})))
    (do
      (assert-registry-open!)
      (update-category-registry-state! assoc-in [:registry id] spec)
      (log/info "Registered ability category" id)
      spec)))

;; ============================================================================
;; Query
;; ============================================================================

(defn get-category [cat-id]
  (get (:registry (category-registry-state-snapshot)) cat-id))

(defn get-all-categories []
  (vals (:registry (category-registry-state-snapshot))))

(defn category-enabled? [cat-id]
  (boolean (:enabled (get-category cat-id))))

(defn get-prog-incr-rate [cat-id]
  (get-in (:registry (category-registry-state-snapshot)) [cat-id :prog-incr-rate] 1.0))
