(ns cn.li.ac.integration.platform-bridge
	"AC bindings for optional integration hooks exposed through mcmod."
	(:require [cn.li.mcmod.platform.integration-runtime :as integration-runtime]
						[cn.li.ac.integration.jei.plugin :as jei-plugin]
						[cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.mcmod.util.log :as log]))

(defonce-guard hooks-installed?)

(defn install-integration-hooks!
	[]
	(with-init-guard hooks-installed?
		(integration-runtime/register-integration-hooks!
			{:jei-get-all-categories jei-plugin/get-all-categories
			 :jei-get-recipes jei-plugin/get-recipes
			 :jei-format-recipe jei-plugin/format-recipe})
		(log/info "AC integration hooks installed"))
	nil)