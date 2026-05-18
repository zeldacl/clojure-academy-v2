(ns cn.li.ac.wireless.data.network-runtime
	"Runtime ticking for wireless networks."
	(:require [cn.li.ac.wireless.config :as network-config]
						[cn.li.ac.wireless.data.network-validation :as validation]
						[cn.li.ac.wireless.data.network-state :as network-state]
						[cn.li.ac.wireless.data.network-energy-balance :as energy-balance]))

(defn tick-wireless-net!
	"Tick the wireless network"
	[network]
	(when (network-state/active? network)
		(when (validation/validate! network)
			(network-state/increment-update-counter! network)
			(when (>= (network-state/get-update-counter network) (network-config/update-interval-ticks))
				(network-state/set-update-counter! network 0)
				(energy-balance/balance-energy! network)))))
