(ns my-mod.item.mat-core
  "Matrix Core - main component for Wireless Matrix
  
  Different damage values represent different tiers:
  - Damage 0: Tier 1 (Basic)
  - Damage 1: Tier 2 (Improved)
  - Damage 2: Tier 3 (Advanced)
  - Damage 3: Tier 4 (Ultimate)"
  (:require [my-mod.item.dsl :as idsl]
            [my-mod.util.log :as log]
            [my-mod.platform.item :as item]
            [clojure.string :as str]))

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

;; Tier 4 disabled - missing mat_core_3.png texture
;; (idsl/defitem mat-core-tier-4 ...)

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn is-mat-core?
  "Check if ItemStack (or item-like value) is a matrix core.

  Accepts either a platform `ItemStack` or an item/spec-like value.
  For ItemStacks we extract the item id from the item's description id
  (e.g. \"item.my_mod.mat_core_0\") and compare against the DSL ids.
  This makes the predicate robust across platform vs. DSL representations.
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
          ;; Match if derived desc contains DSL id (handles different desc formats)
          result (boolean
                   (some (fn [spec]
                           (let [spec-id (:id spec)]
                             (or (= id spec-id)
                                 (and (string? desc) (str/includes? desc spec-id)))))
                         [mat-core-tier-1 mat-core-tier-2 mat-core-tier-3]))]
      ;; (try
      ;;   (log/debug "is-mat-core?" {:stack-class (class item-stack)
      ;;                                :desc desc
      ;;                                :derived-id id
      ;;                                :result result})
      ;;   (catch Throwable _))
      result)))

(defn get-core-level
  "Get core level from ItemStack (1-3)
  Returns 0 if not a core or empty"
  [item-stack]
  (if (and item-stack (is-mat-core? item-stack))
    (+ 1 (item/item-get-damage item-stack))
    0))

(defn init-mat-cores! []
  (log/info "Matrix Cores initialized: 3 tiers"))
