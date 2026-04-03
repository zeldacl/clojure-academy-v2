(ns cn.li.ac.block.phase-gen.schema
  "Phase Generator state schema")

(def phase-gen-schema
  "Schema for phase generator block"
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
    :default 80000.0
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

   {:key :liquid-amount
    :nbt-key "LiquidAmount"
    :type :int
    :default 0
    :persist? true
    :gui-sync? true
    :gui-coerce int}

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

   {:key :has-liquid-source
    :type :boolean
    :default false
    :persist? false
    :gui-sync? true}])
