(ns cn.li.ac.block.energy-converter-elite.config
  "Elite Energy Converter configuration - highest capacity and transfer rates"
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]))

(def descriptors
  [{:key :converter-elite-max-energy
    :section :energy-converter
    :path "energy-converter.elite-max-energy"
    :type :double
    :default 2000000.0
    :min 0.0
    :max 100000000.0
    :comment "Elite energy converter internal IF capacity."}
   {:key :converter-elite-transfer-rate
    :section :energy-converter
    :path "energy-converter.elite-transfer-rate"
    :type :double
    :default 20000.0
    :min 1.0
    :max 1000000.0
    :comment "Maximum IF/tick transfer rate for elite converter."}])

(def default-values
  (into {} (map (juxt :key :default) descriptors)))

(defn- cfg []
  (merge default-values
         (config-reg/get-config-values config-common/wireless-domain)))

(defn max-energy []
  (:converter-elite-max-energy (cfg)))

(defn transfer-rate []
  (:converter-elite-transfer-rate (cfg)))
