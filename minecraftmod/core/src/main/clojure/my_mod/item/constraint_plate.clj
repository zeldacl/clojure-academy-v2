(ns my-mod.item.constraint-plate
  "Constraint Plate - component for Wireless Matrix
  
  Required for Matrix to function (need 3 plates)."
  (:require [my-mod.item.dsl :as idsl]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Constraint Plate Item
;; ============================================================================

(idsl/defitem constraint-plate
  :max-stack-size 64
  :creative-tab :misc
  :properties {:tooltip ["用于无线矩阵的限制板"
                         "需要3个才能激活矩阵"]}
  :on-use (fn [event-data]
            (log/debug "Using constraint plate")))

(defn init-constraint-plate! []
  (log/info "Constraint Plate initialized"))

(defn is-constraint-plate?
  "Check if ItemStack is a constraint plate"
  [item-stack]
  (when item-stack
    (= (.getItem item-stack) constraint-plate)))
