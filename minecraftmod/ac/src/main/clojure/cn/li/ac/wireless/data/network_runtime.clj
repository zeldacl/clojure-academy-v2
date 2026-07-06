(ns cn.li.ac.wireless.data.network-runtime
	"Runtime ticking for wireless networks."
	(:require [cn.li.ac.wireless.config :as network-config]
						[cn.li.ac.wireless.data.entity-commit :as entity-commit]
						[cn.li.ac.wireless.data.network-validation :as validation]
						[cn.li.ac.wireless.data.network-state :as network-state]
						[cn.li.ac.wireless.data.network-energy-balance :as energy-balance]))

(defn tick-wireless-net!
	"Tick the wireless network"
	[network world]
	(let [world-data (:world-data network)
	      network (entity-commit/resolve-network world-data network)]
	  (when (network-state/active? network)
	    (when (validation/validate! network world)
	      (let [network (entity-commit/resolve-network
	                     world-data
	                     (network-state/increment-update-counter! network))]
	        (when (>= (network-state/get-update-counter network)
	                  (network-config/update-interval-ticks))
	          (let [network (entity-commit/resolve-network
	                         world-data
	                         (network-state/set-update-counter! network 0))]
	            (energy-balance/balance-energy! network world))))))))
