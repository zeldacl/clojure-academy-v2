(ns cn.li.ac.integration.block.energy-converter.schema
  "Pure data schema for converter state."
  (:require [cn.li.ac.integration.block.energy-converter.config :as ec-config]))

(def default-state
  {:energy 0.0
   :max-energy (double ec-config/energy-capacity)
   :wireless-enabled true
  :wireless-bandwidth (double ec-config/transfer-bandwidth)})

(def energy-converter-gui-schema
  [{:key :energy
    :type :double
    :default 0.0
    :persist? false
    :gui-sync? true
    :gui-coerce double}

   {:key :max-energy
    :type :double
    :default (double ec-config/energy-capacity)
    :persist? false
    :gui-sync? true
    :gui-coerce double}

   {:key :wireless-enabled
    :type :boolean
    :default true
    :persist? false
    :gui-sync? true}])