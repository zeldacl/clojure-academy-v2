(ns cn.li.ac.block.ability-interferer.schema
  "Ability Interferer state schema")

(def ability-interferer-schema
  "Schema for ability interferer block"
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

   {:key :range
    :nbt-key "Range"
    :type :double
    :default 20.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :network-editable? true
    :network-msg :change-range}

   {:key :enabled
    :nbt-key "Enabled"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true
    :network-editable? true
    :network-msg :toggle-enabled}

   {:key :whitelist
    :nbt-key "Whitelist"
    :type :string-list
    :default []
    :persist? true
    :gui-sync? true}

   {:key :affected-player-count
    :nbt-key "AffectedPlayerCount"
    :type :int
    :default 0
    :persist? false
    :gui-sync? true
    :gui-coerce int}

   {:key :inventory
    :nbt-key "Inventory"
    :type :item-list
    :default []
    :persist? true
    :size 1}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}])
