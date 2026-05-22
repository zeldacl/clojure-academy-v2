(ns cn.li.ac.integration.block.energy-converter.platform-bridge
	"AC energy converter bindings for platform-neutral integration hooks."
	(:require [cn.li.mcmod.platform.energy-integration :as energy-integration]
	          [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.ac.integration.block.energy-converter.config :as config]
						[cn.li.mcmod.util.log :as log]))

(defonce-guard hooks-installed?)

(defn install-energy-integration-hooks!
	[]
	(with-init-guard hooks-installed?
		(energy-integration/register-energy-integration-hooks!
			{:forge-energy-conversion-rate (fn [] (double (config/rf-conversion-ratio)))
			 :ic2-energy-conversion-rate (fn [] (double (config/eu-conversion-ratio)))})
		(log/info "AC energy integration hooks installed"))
	nil)