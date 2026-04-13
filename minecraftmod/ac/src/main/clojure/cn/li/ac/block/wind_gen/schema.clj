(ns cn.li.ac.block.wind-gen.schema
  "Wind Generator state schemas aligned with AcademyCraft windgen behavior."
  (:require [cn.li.mcmod.block.inventory-helpers :as inv-helpers]))

;; ============================================================================
;; Wind Generator Main Schema
;; ============================================================================

(def wind-gen-main-schema
  "Main block stores fan inventory + structure/obstacle state."
  [{:key :inventory
    :nbt-key "Inventory"
    :type :inventory
    :default [nil]
    :persist? true
    :gui-sync? false
    :load-fn inv-helpers/load-inventory
    :save-fn inv-helpers/save-inventory}

   {:key :complete
    :nbt-key "Complete"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true}

   {:key :no-obstacle
    :nbt-key "NoObstacle"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true}

   {:key :fan-installed
    :nbt-key "FanInstalled"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true}

   {:key :status
    :nbt-key "Status"
    :type :string
    :default "STOPPED"
    :persist? true
    :gui-sync? true}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}

   {:key :wind-change-ticker
    :type :int
    :default 0
    :persist? false}

   {:key :structure-valid
    :type :boolean
    :default false
    :persist? false
    :gui-sync? true}])

;; ============================================================================
;; Wind Generator Base Schema
;; ============================================================================

(def wind-gen-base-schema
  "Base block stores energy, battery slot, and tower completeness state."
  [{:key :energy
    :nbt-key "Energy"
    :type :double
    :default 0.0
    :persist? true
    :gui-sync? true
    :gui-coerce double}

   {:key :max-energy
    :nbt-key "MaxEnergy"
    :type :double
    :default 20000.0
    :persist? true
    :gui-sync? true
    :gui-coerce double}

     {:key :gen-speed
    :nbt-key "GenSpeed"
    :type :double
    :default 0.0
    :persist? true
    :gui-sync? true
    :gui-coerce double}

     {:key :inventory
    :nbt-key "Inventory"
    :type :inventory
    :default [nil]
    :persist? true
    :gui-sync? false
    :load-fn inv-helpers/load-inventory
    :save-fn inv-helpers/save-inventory}

     {:key :completeness
    :nbt-key "Completeness"
    :type :string
    :default "BASE_ONLY"
    :persist? true
    :gui-sync? true}

   {:key :status
    :nbt-key "Status"
    :type :string
    :default "IDLE"
    :persist? true
    :gui-sync? true}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}

   {:key :structure-valid
    :type :boolean
    :default false
    :persist? false
    :gui-sync? true}

   {:key :main-pos-x
    :nbt-key "MainPosX"
    :type :int
    :default 0
    :persist? true}

   {:key :main-pos-y
    :nbt-key "MainPosY"
    :type :int
    :default 0
    :persist? true}

   {:key :main-pos-z
    :nbt-key "MainPosZ"
    :type :int
    :default 0
    :persist? true}])

;; ============================================================================
;; Wind Generator Pillar Schema
;; ============================================================================

(def wind-gen-pillar-schema
  "Pillar block only tracks lightweight structure-check state."
  [{:key :structure-valid
    :type :boolean
    :default false
    :persist? false}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}])
