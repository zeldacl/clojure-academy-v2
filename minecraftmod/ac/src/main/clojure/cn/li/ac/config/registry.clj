(ns cn.li.ac.config.registry
	(:require [cn.li.ac.config.common :as config-common]
						[cn.li.ac.wireless.data.network-config :as network-config]
						[cn.li.ac.block.wireless-matrix.config :as matrix-config]
						[cn.li.ac.block.wireless-node.config :as node-config]
						[cn.li.ac.block.solar-gen.config :as solar-config]
						[cn.li.ac.wireless.search-config :as search-config]
						[cn.li.mcmod.config.registry :as config-reg]))

(def wireless-descriptors
	(vec (concat network-config/descriptors
							 matrix-config/descriptors
							 node-config/descriptors
							 solar-config/descriptors
							 search-config/descriptors)))

(def wireless-default-values
	(merge network-config/default-values
				 matrix-config/default-values
				 node-config/default-values
				 solar-config/default-values
				 search-config/default-values))

(defn init-configs! []
	(config-reg/register-config-descriptors!
		config-common/wireless-domain
		wireless-descriptors)
	(config-reg/ensure-default-values!
		config-common/wireless-domain
		wireless-default-values)
	nil)