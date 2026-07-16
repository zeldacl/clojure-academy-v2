(ns cn.li.ac.block.metal-former.schema
  "Metal Former state schema")

(def metal-former-schema
  "Schema for metal former block"
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
    :default 3000.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :gui-data-slot-scale 100}

     {:key :work-counter
    :nbt-key "WorkCounter"
    :type :int
    :default 0
    :persist? true
    :gui-sync? true
    :gui-coerce int}

     {:key :mode
    :nbt-key "Mode"
    :type :string
    :default "plate"
    :persist? true
    :gui-sync? true
    :gui-data-slot? false}

   {:key :working
    :nbt-key "Working"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true
    :gui-coerce boolean}

   {:key :current-recipe-id
    :nbt-key "CurrentRecipeId"
    :type :string
    :default ""
    :persist? true}

   {:key :inventory
    :nbt-key "Inventory"
    :type :item-list
    :default []
    :persist? true
    :size 3}

   {:key :facing
    :nbt-key "Facing"
    :type :string
    :default "north"
    :persist? true
    :block-state {:prop "facing"
                 :type :horizontal-facing
                 :default "north"}}])
