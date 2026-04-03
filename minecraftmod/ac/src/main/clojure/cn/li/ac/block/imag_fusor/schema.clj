(ns cn.li.ac.block.imag-fusor.schema
  "Imaginary Fusor state schema")

(def imag-fusor-schema
  "Schema for imaginary fusor block"
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

   {:key :crafting-progress
    :nbt-key "CraftingProgress"
    :type :int
    :default 0
    :persist? true
    :gui-sync? true
    :gui-coerce int}

   {:key :max-progress
    :nbt-key "MaxProgress"
    :type :int
    :default 100
    :persist? true
    :gui-sync? true
    :gui-coerce int}

   {:key :working
    :nbt-key "Working"
    :type :boolean
    :default false
    :persist? true
    :gui-sync? true}

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
    :size 4}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}

   {:key :facing
    :nbt-key "Facing"
    :type :string
    :default "north"
    :persist? true}])
