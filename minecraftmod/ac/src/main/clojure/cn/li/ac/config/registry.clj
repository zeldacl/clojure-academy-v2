(ns cn.li.ac.config.registry
	(:require [cn.li.ac.wireless.config :as wireless-config]
						[cn.li.ac.block.solar-gen.config :as solar-config]
						[cn.li.ac.config.common :as config-common]
						[cn.li.mcmod.config.registry :as config-reg]))

(def wireless-descriptors
	(vec (concat wireless-config/descriptors
							 solar-config/descriptors)))

(def wireless-default-values
	(merge wireless-config/default-values
				 solar-config/default-values))

(defn init-configs! []
	(config-reg/register-config-descriptors!
		config-common/wireless-domain
		wireless-descriptors)
	(config-reg/ensure-default-values!
		config-common/wireless-domain
		wireless-default-values)
	nil)