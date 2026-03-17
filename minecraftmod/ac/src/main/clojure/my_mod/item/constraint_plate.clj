(ns my-mod.item.constraint-plate
  "Constraint Plate - component for Wireless Matrix
  
  Required for Matrix to function (need 3 plates)."
  (:require [my-mod.item.dsl :as idsl]
            [my-mod.util.log :as log]
            [clojure.string :as str]))

;; ============================================================================
;; Constraint Plate Item
;; ============================================================================

(idsl/defitem constraint-plate
  :id "constraint_plate"
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["用于无线矩阵的限制板"
                         "需要3个才能激活矩阵"]
               :model-texture "constraint_plate"}
  :on-use (fn [event-data]
            (log/debug "Using constraint plate")))

(defn init-constraint-plate! []
  (log/info "Constraint Plate initialized"))

(defn is-constraint-plate?
  "Check if ItemStack (or item-like value) is a constraint plate.

  Accepts either a platform `ItemStack` or an item/spec-like map.
  For ItemStacks we extract the description id and compare against the
  DSL `:id` to make the predicate robust across representations.
  "
  [item-stack]
  (when item-stack
    (let [id-from-spec #(when (map? %) (:id %))
          desc (try
                 (let [item (.getItem item-stack)]
                   (str (.getDescriptionId item)))
                 (catch Throwable _ nil))
          id-from-stack (when desc (last (str/split desc #"\\.")))
          id (or id-from-stack (id-from-spec item-stack))
          result (boolean
                  (or (= id (:id constraint-plate))
                      (and (string? desc) (str/includes? desc (:id constraint-plate)))))]
      (try
        (log/debug "is-constraint-plate?" {:stack-class (class item-stack)
                                            :desc desc
                                            :derived-id id
                                            :result result})
        (catch Throwable _))
      result)))
