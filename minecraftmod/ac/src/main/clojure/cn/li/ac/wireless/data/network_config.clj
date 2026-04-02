(ns cn.li.ac.wireless.data.network-config
	(:require [cn.li.ac.config.common :as config-common]
						[cn.li.mcmod.config.registry :as config-reg]))

(def descriptors
	[{:key :network-update-interval-ticks
		:section :network
		:path "network.update-interval-ticks"
		:type :int
		:default 40
		:min 1
		:max 1200
		:comment "Ticks between wireless network energy balance passes."}
	 {:key :network-buffer-max
		:section :network
		:path "network.buffer-max"
		:type :double
		:default 2000.0
		:min 0.0
		:max 1000000.0
		:comment "Maximum transit buffer stored by a wireless network."}])

(def default-values
	(into {} (map (juxt :key :default) descriptors)))

(defn- cfg []
	(merge default-values
				 (config-reg/get-config-values config-common/wireless-domain)))

(defn update-interval-ticks []
	(:network-update-interval-ticks (cfg)))

(defn buffer-max []
	(:network-buffer-max (cfg)))