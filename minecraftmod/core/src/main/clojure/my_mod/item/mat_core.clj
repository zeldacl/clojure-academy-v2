(ns my-mod.item.mat-core
  "Matrix Core - main component for Wireless Matrix
  
  Different damage values represent different tiers:
  - Damage 0: Tier 1 (Basic)
  - Damage 1: Tier 2 (Improved)
  - Damage 2: Tier 3 (Advanced)
  - Damage 3: Tier 4 (Ultimate)"
  (:require [my-mod.item.dsl :as idsl]
            [my-mod.util.log :as log]))

;; ============================================================================
;; Matrix Core Tiers
;; ============================================================================

(def core-tiers
  {:tier-1 {:damage 0
            :name "基础矩阵核心"
            :tooltip ["等级: 1"
                     "容量倍率: 8"
                     "带宽倍率: 60"
                     "范围倍率: 24"]}
   :tier-2 {:damage 1
            :name "改进矩阵核心"
            :tooltip ["等级: 2"
                     "容量倍率: 16"
                     "带宽倍率: 240"
                     "范围倍率: 33.9"]}
   :tier-3 {:damage 2
            :name "高级矩阵核心"
            :tooltip ["等级: 3"
                     "容量倍率: 24"
                     "带宽倍率: 540"
                     "范围倍率: 41.6"]}
   :tier-4 {:damage 3
            :name "终极矩阵核心"
            :tooltip ["等级: 4"
                     "容量倍率: 32"
                     "带宽倍率: 960"
                     "范围倍率: 48.0"]}})

;; ============================================================================
;; Matrix Core Items
;; ============================================================================

(idsl/defitem mat-core-tier-1
  :id "mat_core_0"
  :max-stack-size 1
  :creative-tab :misc
  :max-damage 0
  :properties {:tooltip (get-in core-tiers [:tier-1 :tooltip])
               :display-name (get-in core-tiers [:tier-1 :name])
               :model-texture "mat_core_0"})

(idsl/defitem mat-core-tier-2
  :id "mat_core_1"
  :max-stack-size 1
  :creative-tab :misc
  :max-damage 1
  :properties {:tooltip (get-in core-tiers [:tier-2 :tooltip])
               :display-name (get-in core-tiers [:tier-2 :name])
               :model-texture "mat_core_1"})

(idsl/defitem mat-core-tier-3
  :id "mat_core_2"
  :max-stack-size 1
  :creative-tab :misc
  :max-damage 2
  :properties {:tooltip (get-in core-tiers [:tier-3 :tooltip])
               :display-name (get-in core-tiers [:tier-3 :name])
               :model-texture "mat_core_2"})

(idsl/defitem mat-core-tier-4
  :id "mat_core_3"
  :max-stack-size 1
  :creative-tab :misc
  :max-damage 3
  :properties {:tooltip (get-in core-tiers [:tier-4 :tooltip])
               :display-name (get-in core-tiers [:tier-4 :name])
               :model-texture "mat_core_3"})

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn is-mat-core?
  "Check if ItemStack is a matrix core"
  [item-stack]
  (when item-stack
    (let [item (.getItem item-stack)]
      (or (= item mat-core-tier-1)
          (= item mat-core-tier-2)
          (= item mat-core-tier-3)
          (= item mat-core-tier-4)))))

(defn get-core-level
  "Get core level from ItemStack (1-4)
  Returns 0 if not a core or empty"
  [item-stack]
  (if (and item-stack (is-mat-core? item-stack))
    (+ 1 (.getItemDamage item-stack))
    0))

(defn init-mat-cores! []
  (log/info "Matrix Cores initialized: 4 tiers"))
