(ns cn.li.ac.block.wireless-matrix.config
	(:require [cn.li.ac.config.common :as config-common]
						[cn.li.mcmod.config.registry :as config-reg]))

(def descriptors
	[{:key :matrix-capacity-per-core-level
		:section :matrix
		:path "matrix.capacity-per-core-level"
		:type :int
		:default 8
		:min 0
		:max 512
		:comment "Connected node capacity granted by each matrix core level."}
	 {:key :matrix-bandwidth-factor
		:section :matrix
		:path "matrix.bandwidth-factor"
		:type :int
		:default 60
		:min 0
		:max 100000
		:comment "Matrix bandwidth uses coreLevel^2 * bandwidth-factor."}
	 {:key :matrix-range-base
		:section :matrix
		:path "matrix.range-base"
		:type :double
		:default 24.0
		:min 0.0
		:max 4096.0
		:comment "Matrix range uses range-base * sqrt(coreLevel)."}
	 {:key :matrix-gui-sync-interval
		:section :tick
		:path "tick.matrix-gui-sync-interval"
		:type :int
		:default 15
		:min 1
		:max 1200
		:comment "Ticks between matrix GUI sync broadcasts."}
	 {:key :matrix-validate-interval
		:section :tick
		:path "tick.matrix-validate-interval"
		:type :int
		:default 20
		:min 1
		:max 1200
		:comment "Ticks between matrix multiblock structure validation passes."}])

(def default-values
	(into {} (map (juxt :key :default) descriptors)))

(defn- cfg []
	(merge default-values
				 (config-reg/get-config-values config-common/wireless-domain)))

(defn capacity-per-core-level []
	(:matrix-capacity-per-core-level (cfg)))

(defn bandwidth-factor []
	(:matrix-bandwidth-factor (cfg)))

(defn range-base []
	(:matrix-range-base (cfg)))

(defn gui-sync-interval []
	(:matrix-gui-sync-interval (cfg)))

(defn validate-interval []
	(:matrix-validate-interval (cfg)))