(ns cn.li.ac.integration.platform-bridge
	"AC bindings for optional integration hooks exposed through mcmod."
	(:require [cn.li.mcmod.platform.integration-runtime :as integration-runtime]
						[cn.li.ac.integration.jei.plugin :as jei-plugin]
						[cn.li.ac.integration.crafttweaker.recipes :as ct-recipes]
						[cn.li.ac.integration.crafttweaker.bridge :as ct-bridge]
						[cn.li.mcmod.util.log :as log]))

(defonce ^:private hooks-installed? (atom false))

(defn install-integration-hooks!
	[]
	(when (compare-and-set! hooks-installed? false true)
		(integration-runtime/register-integration-hooks!
			{:jei-get-all-categories jei-plugin/get-all-categories
			 :jei-get-recipes jei-plugin/get-recipes
			 :jei-format-recipe jei-plugin/format-recipe
			 :crafttweaker-add-fusor-recipe! ct-recipes/add-fusor-recipe!
			 :crafttweaker-remove-fusor-recipe! ct-recipes/remove-fusor-recipe!
			 :crafttweaker-add-former-recipe! ct-recipes/add-former-recipe!
			 :crafttweaker-remove-former-recipe!
			 (fn [output-item mode]
				 (ct-recipes/remove-former-recipe! output-item mode))
			 :crafttweaker-describe-recipe ct-bridge/describe-recipe})
		(log/info "AC integration hooks installed"))
	nil)