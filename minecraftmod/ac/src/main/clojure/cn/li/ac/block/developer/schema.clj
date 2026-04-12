(ns cn.li.ac.block.developer.schema
  "Developer state schema (tile + TechUI container)."
  (:require [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.block.inventory-helpers :as inv-helpers]
            [cn.li.ac.config.nbt-keys :as nbt-keys]))

(def developer-schema
  "Schema for developer controller block (NBT + GUI-synced server fields)."
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

   {:key :wireless-bandwidth
    :nbt-key "WirelessBW"
    :type :double
    :default 1000.0
    :persist? true
    :gui-sync? true
    :gui-coerce double
    :doc "IF per tick cap for wireless inject (receiver path)."}

   {:key :tier
    :nbt-key "Tier"
    :type :string
    :default "normal"
    :persist? true
    :gui-sync? true
    :gui-coerce str}

   {:key :user-uuid
    :nbt-key "UserUUID"
    :type :string
    :default ""
    :persist? true
    :gui-sync? true}

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

   {:key :inventory
    :nbt-key (nbt-keys/get-key :developer-inventory)
    :type :inventory
    :default [nil nil]
    :persist? true
    :gui-sync? false
    :load-fn inv-helpers/load-inventory
    :save-fn inv-helpers/save-inventory
    :doc "Two generic item slots (classic developer inventory size)."}

   {:key :update-ticker
    :type :int
    :default 0
    :persist? false}

   {:key :wireless-inject-this-tick
    :type :double
    :default 0.0
    :persist? false
    :gui-sync? true
    :gui-coerce double
    :doc "IF accepted from wireless this game tick (server, flushed each tick)."}

   {:key :wireless-inject-last-tick
    :type :double
    :default 0.0
    :persist? false
    :gui-sync? true
    :gui-coerce double
    :doc "IF accepted from wireless previous tick (for classic sync-rate bar)."}])

(def ^:private gui-only-fields
  "Client container atoms (TechUI / sync helpers)."
  [{:key :sync-ticker
    :gui-only? true
    :gui-init (fn [_] 0)
    :gui-sync? false
    :gui-coerce int
    :gui-close-reset 0}

   {:key :tab-index
    :gui-only? true
    :gui-init (fn [_] 0)
    :gui-sync? false
    :gui-coerce int
    :gui-close-reset 0}])

(def unified-developer-schema
  (state-schema/merge-field-definitions
    [developer-schema gui-only-fields]))
