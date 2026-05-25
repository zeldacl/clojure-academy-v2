(ns cn.li.ac.integration.block.energy-converter.platform-bridge
	"AC energy converter bindings for platform-neutral integration hooks."
	(:require [cn.li.mcmod.platform.energy-integration :as energy-integration]
	          [cn.li.mcmod.content.registry :as content-registry]
	          [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.ac.integration.block.energy-converter.config :as config]
						[cn.li.mcmod.util.log :as log]))

(defonce-guard hooks-installed?)

(def ^:private integration-descriptors
	[{:id :ac/forge-energy-bridge
		:kind :forge-energy-capability
		:source {:capability-key :energy-converter}
		:target {:capability-key :forge-energy
					 :tile-ids ["rf-input" "rf-output"]}}
	 {:id :ac/ic2-energy-bridge
		:kind :ic2-energy-capability
		:source {:capability-key :energy-converter}
		:target {:modes {"import" {:tile-ids ["eu-input"]}
							 "export" {:tile-ids ["eu-output"]}}}}])

(defn- install-integration-descriptors!
	[]
	(doseq [descriptor integration-descriptors]
		(content-registry/register-descriptor! :integration descriptor))
	nil)

(defn install-energy-integration-hooks!
	[]
	(with-init-guard hooks-installed?
		(energy-integration/register-energy-integration-hooks!
			{:forge-energy-conversion-rate (fn [] (double (config/rf-conversion-ratio)))
			 :ic2-energy-conversion-rate (fn [] (double (config/eu-conversion-ratio)))})
		(install-integration-descriptors!)
		(log/info "AC energy integration hooks installed"))
	nil)