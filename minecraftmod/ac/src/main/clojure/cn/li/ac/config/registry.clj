(ns cn.li.ac.config.registry
	(:require [cn.li.ac.config.gameplay :as gameplay-config]
						[cn.li.ac.ability.config :as ability-config]
						[cn.li.ac.ability.skill-config :as ability-skill-config]
						[cn.li.ac.wireless.config :as wireless-config]
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
	(config-reg/register-config-descriptors!
		config-common/gameplay-domain
		gameplay-config/descriptors)
	(config-reg/ensure-default-values!
		config-common/gameplay-domain
		gameplay-config/default-values)
	(config-reg/register-config-descriptors!
		config-common/ability-domain
		ability-config/descriptors)
	(config-reg/ensure-default-values!
		config-common/ability-domain
		ability-config/default-values)
	(doseq [category-id ability-skill-config/category-ids
				:let [domain (ability-skill-config/category-domain category-id)]]
		(config-reg/register-config-descriptors!
			domain
			(get ability-skill-config/descriptors-by-category category-id))
		(config-reg/ensure-default-values!
			domain
			(get ability-skill-config/default-values-by-category category-id)))
	nil)