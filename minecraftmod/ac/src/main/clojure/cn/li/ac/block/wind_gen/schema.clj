(ns cn.li.ac.block.wind-gen.schema
  "Wind Generator state schemas for main, base, and pillar blocks")

;; ============================================================================
;; Wind Generator Main Schema
;; ============================================================================

(def wind-gen-main-schema
  "Schema for wind generator main block (top part with blades)"
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
    :default 50000.0
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

   {:key :wind-multiplier
    :nbt-key "WindMultiplier"
    :type :double
    :default 1.0
    :persist? true
    :gui-sync? true
    :gui-coerce double}

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
  "Schema for wind generator base block (bottom part with energy storage)"
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
    :default 100000.0
    :persist? true
    :gui-sync? true
    :gui-coerce double}

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
  "Schema for wind generator pillar block (support structure)"
  [{:key :structure-valid
    :type :boolean
    :default false
    :persist? false}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}])
