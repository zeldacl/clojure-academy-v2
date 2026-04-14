(ns cn.li.ac.integration.block.energy-converter.schema
  "Pure data schema for converter state."
  (:require [cn.li.ac.integration.block.energy-converter.config :as ec-config]))

(def default-state
  {:energy 0.0
   :max-energy (double ec-config/energy-capacity)
   :wireless-enabled true
  :wireless-bandwidth (double ec-config/transfer-bandwidth)})