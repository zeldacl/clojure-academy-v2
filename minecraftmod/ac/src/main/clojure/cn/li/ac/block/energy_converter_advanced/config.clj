(ns cn.li.ac.block.energy-converter-advanced.config
  "Advanced Energy Converter configuration - higher capacity and transfer rates"
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]))

(def descriptors
  [{:key :converter-advanced-max-energy
    :section :energy-converter
    :path "energy-converter.advanced-max-energy"
    :type :double
    :default 500000.0
    :min 0.0
    :max 100000000.0
    :comment "Advanced energy converter internal IF capacity."}
   {:key :converter-advanced-transfer-rate
    :section :energy-converter
    :path "energy-converter.advanced-transfer-rate"
    :type :double
    :default 5000.0
    :min 1.0
    :max 1000000.0
    :comment "Maximum IF/tick transfer rate for advanced converter."}])

(def default-values
  (into {} (map (juxt :key :default) descriptors)))

(defn- cfg []
  (merge default-values
         (config-reg/get-config-values config-common/wireless-domain)))

(defn max-energy []
  (:converter-advanced-max-energy (cfg)))

(defn transfer-rate []
  (:converter-advanced-transfer-rate (cfg)))
