(ns cn.li.ac.ability.category
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

(defonce category-registry (atom {}))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-category!
  "Register a category spec. Silently overwrites on hot-reload."
  [{:keys [id] :as spec}]
  {:pre [(keyword? id) (string? (:name-key spec))]}
  (swap! category-registry assoc id spec)
  (log/info "Registered ability category" id)
  spec)

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
