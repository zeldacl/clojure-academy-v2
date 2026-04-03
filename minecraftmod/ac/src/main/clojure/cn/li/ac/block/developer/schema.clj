(ns cn.li.ac.block.developer.schema
  "Developer state schema")

(def developer-schema
  "Schema for developer controller block"
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

   {:key :tier
    :nbt-key "Tier"
    :type :string
    :default "normal"
    :persist? true
    :gui-sync? true}

   {:key :user-uuid
    :nbt-key "UserUUID"
    :type :string
    :default ""
    :persist? true}

   {:key :user-name
    :nbt-key "UserName"
    :type :string
    :default ""
    :persist? true
    :gui-sync? true}

   {:key :development-progress
    :nbt-key "DevelopmentProgress"
    :type :double
    :default 0.0
    :persist? true
    :gui-sync? true
    :gui-coerce double}

   {:key :is-developing
    :nbt-key "IsDeveloping"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true}

   {:key :structure-valid
    :nbt-key "StructureValid"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}])
