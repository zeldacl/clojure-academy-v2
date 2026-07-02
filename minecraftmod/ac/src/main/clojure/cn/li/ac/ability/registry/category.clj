(ns cn.li.ac.ability.registry.category
  "Categories group skills into thematic sets (e.g., esper, radiation, mist).
  Each category definition is a plain map stored in category-registry.

  Schema per category:
    {:id            keyword        ; unique identifier, e.g. :esper
     :name-key      string         ; i18n key
     :icon          string         ; resource path to icon texture
     :color         [r g b a]      ; RGBA floats for UI tinting
     :prog-incr-rate float         ; experience gain rate for this category
     :enabled       bool}

  Registry stored in Framework [:registry :categories]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Registry — Framework [:registry :categories]
;; ============================================================================

(def ^:private cat-path [:registry :categories])

(defn- category-registry-state-snapshot []
  (if-let [fw-atom fw/*framework*]
    (get-in @fw-atom cat-path {:registry {} :frozen? false})
    {:registry {} :frozen? false}))

(defn- update-category-registry-state! [f & args]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom update-in cat-path
           (fn [current] (apply f (or current {:registry {} :frozen? false}) args))))
  nil)

(defn- assert-registry-open! []
  (when (:frozen? (category-registry-state-snapshot))
    (throw (ex-info "Category registry is frozen" {}))))

;; ============================================================================
;; Backward-compatible install (writes to Framework)
;; Backward-compatible factory
(defn create-category-registry-runtime
  ([]
   {::category-registry-runtime true
    :state* (atom {:registry {} :frozen? false})})
  ([{:keys [state*] :or {state* (atom {:registry {} :frozen? false})}}]
   {::category-registry-runtime true :state* state*}))

;; ============================================================================

(defn install-category-registry-runtime!
  "Backward-compatible install. Writes to Framework [:registry :categories]."
  [runtime]
  (when-let [fw-atom fw/*framework*]
    (when-let [state* (:state* runtime)]
      (swap! fw-atom assoc-in cat-path @state*)))
  runtime)

;; ============================================================================
;; Query API
;; ============================================================================

(defn category-registry-snapshot []
  (:registry (category-registry-state-snapshot)))

(defn reset-category-registry-for-test!
  ([]
   (reset-category-registry-for-test! {}))
  ([snapshot]
   (when-let [fw-atom fw/*framework*]
     (swap! fw-atom assoc-in cat-path {:registry (or snapshot {}) :frozen? false}))
   nil))

(defn freeze-category-registry! []
  (update-category-registry-state! assoc :frozen? true)
  nil)

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-category!
  "Register a category spec with idempotent duplicate handling."
  [{:keys [id] :as spec}]
  (when-not (and (keyword? id) (string? (:name-key spec)))
    (throw (IllegalArgumentException. "register-category!: id must be keyword, :name-key must be string")))
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
