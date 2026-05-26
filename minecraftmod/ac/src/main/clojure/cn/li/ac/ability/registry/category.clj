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

(defonce ^:private category-registry (atom {}))
(defonce ^:private category-registry-frozen? (atom false))

(defn- assert-registry-open!
  []
  (when @category-registry-frozen?
    (throw (ex-info "Category registry is frozen" {}))))

(defn category-registry-snapshot
  []
  @category-registry)

(defn reset-category-registry-for-test!
  ([]
   (reset-category-registry-for-test! {}))
  ([snapshot]
   (reset! category-registry (or snapshot {}))
   (reset! category-registry-frozen? false)
   nil))

(defn freeze-category-registry!
  []
  (reset! category-registry-frozen? true)
  nil)

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-category!
  "Register a category spec with idempotent duplicate handling."
  [{:keys [id] :as spec}]
  {:pre [(keyword? id) (string? (:name-key spec))]}
  (if-let [existing (get @category-registry id)]
    (if (= existing spec)
      existing
      (throw (ex-info "Conflicting ability category id"
                      {:id id :existing existing :new spec})))
    (do
      (assert-registry-open!)
      (swap! category-registry assoc id spec)
      (log/info "Registered ability category" id)
      spec)))

;; ============================================================================
;; Query
;; ============================================================================

(defn get-category [cat-id]
  (get @category-registry cat-id))

(defn get-all-categories []
  (vals @category-registry))

(defn category-enabled? [cat-id]
  (boolean (:enabled (get-category cat-id))))

(defn get-prog-incr-rate [cat-id]
  (get-in @category-registry [cat-id :prog-incr-rate] 1.0))
