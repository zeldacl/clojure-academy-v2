(ns cn.li.ac.wireless.data.network-runtime
	"Runtime ticking for wireless networks."
	(:require [cn.li.ac.wireless.data.network-config :as network-config]
						[cn.li.ac.wireless.data.network-validation :as validation]
						[cn.li.ac.wireless.data.network-membership :as membership]
						[cn.li.ac.wireless.data.network-energy-balance :as energy-balance]))

(defn tick-wireless-net!
	"Tick the wireless network"
	[network]
	(when-not @(:disposed network)
		(when (validation/validate! network)
			(swap! (:update-counter network) inc)
			(when (>= @(:update-counter network) (network-config/update-interval-ticks))
				(reset! (:update-counter network) 0)
				(energy-balance/balance-energy! network))
			(membership/cleanup-removed-nodes! network))))
