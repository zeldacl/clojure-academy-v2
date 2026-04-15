(ns cn.li.ac.integration.block.energy-converter.platform-bridge
	"AC energy converter bindings for platform-neutral integration hooks."
	(:require [cn.li.mcmod.platform.energy-integration :as energy-integration]
						[cn.li.ac.integration.block.energy-converter.config :as config]
						[cn.li.mcmod.util.log :as log]))

(defonce ^:private hooks-installed? (atom false))

(defn install-energy-integration-hooks!
	[]
	(when (compare-and-set! hooks-installed? false true)
		(energy-integration/register-energy-integration-hooks!
			{:forge-energy-conversion-rate (fn [] (double config/rf-conversion-ratio))})
		(log/info "AC energy integration hooks installed"))
	nil)