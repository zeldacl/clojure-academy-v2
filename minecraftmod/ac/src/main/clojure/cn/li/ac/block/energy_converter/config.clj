(ns cn.li.ac.block.energy-converter.config
  "Energy Converter configuration - capacity, conversion rates, transfer rates"
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]))

(def descriptors
  [{:key :converter-max-energy
    :section :energy-converter
    :path "energy-converter.max-energy"
    :type :double
    :default 100000.0
    :min 0.0
    :max 100000000.0
    :comment "Energy converter internal IF capacity."}
   {:key :converter-fe-conversion-rate
    :section :energy-converter
    :path "energy-converter.fe-conversion-rate"
    :type :double
    :default 4.0
    :min 0.1
    :max 1000.0
    :comment "Forge Energy conversion rate (1 IF = X FE)."}
   {:key :converter-transfer-rate
    :section :energy-converter
    :path "energy-converter.transfer-rate"
    :type :double
    :default 1000.0
    :min 1.0
    :max 1000000.0
    :comment "Maximum IF/tick transfer rate for item charging."}
   {:key :converter-tick-interval
    :section :energy-converter
    :path "energy-converter.tick-interval"
    :type :int
    :default 20
    :min 1
    :max 200
    :comment "Ticks between energy converter updates."}])

(def default-values
  (into {} (map (juxt :key :default) descriptors)))

(defn- cfg []
  (merge default-values
         (config-reg/get-config-values config-common/wireless-domain)))

(defn max-energy []
  (:converter-max-energy (cfg)))

(defn fe-conversion-rate []
  (:converter-fe-conversion-rate (cfg)))

(defn transfer-rate []
  (:converter-transfer-rate (cfg)))

(defn tick-interval []
  (:converter-tick-interval (cfg)))
