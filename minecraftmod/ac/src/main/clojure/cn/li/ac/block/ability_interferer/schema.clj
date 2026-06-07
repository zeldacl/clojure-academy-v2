(ns cn.li.ac.block.ability-interferer.schema
  "Ability Interferer state schema")

(defonce ability-interferer-schema
  [{:key :energy
    :nbt-key "Energy"
    :type :double
    :default 0.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :gui-data-slot-scale 100}

   {:key :max-energy
    :nbt-key "MaxEnergy"
    :type :double
    :default 10000.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :gui-data-slot-scale 100}

   {:key :range
    :nbt-key "Range"
    :type :double
    :default 10.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :gui-data-slot-scale 100
    :network-editable? true
    :network-msg :change-range}

   {:key :enabled
    :nbt-key "Enabled"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true
    :gui-coerce boolean
    :block-state {:prop "on"
            :type :boolean
            :default false}
    :network-editable? true
    :network-msg :toggle-enabled}

     {:key :placer-name
    :nbt-key "Placer"
    :type :string
    :default ""
    :persist? true
    :gui-sync? true
    :gui-data-slot? false}

   {:key :whitelist
    :nbt-key "Whitelist"
    :type :string-list
    :default []
    :persist? true
    :gui-sync? true
    :gui-data-slot? false}

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
    :default [nil]
    :persist? true
    :size 1}

     {:key :affected-player-uuids
    :type :string-list
    :default []
    :persist? false}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}])
