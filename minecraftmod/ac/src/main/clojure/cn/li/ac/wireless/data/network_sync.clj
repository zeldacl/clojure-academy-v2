(ns cn.li.ac.wireless.data.network-sync
	"Pure sync payload helpers for wireless network state."
	(:require [cn.li.ac.wireless.data.network-state :as runtime-state]))

(defn network->sync-payload
	"Project either a pure domain network map or runtime network state into a sync map."
	[network]
	(cond
		(and (map? network) (:energy network) (:nodes network))
		{:id (:id network)
		 :ssid (:ssid network)
		 :password (:password network)
		 :node-count (count (:nodes network))
		 :energy-current (get-in network [:energy :current] 0.0)
		 :energy-max (get-in network [:energy :max] 0.0)
		 :metadata (:metadata network)}

		(instance? cn.li.ac.wireless.data.network_state.WirelessNet network)
		{:ssid (runtime-state/get-ssid network)
		 :password (runtime-state/get-password network)
		 :node-count (runtime-state/get-load network)
		 :energy-max (runtime-state/get-capacity network)}

		:else
		nil))

(defn merge-sync-payload
	"Merge a sync payload into a pure network map."
	[network payload]
	(cond-> network
		(:ssid payload) (assoc :ssid (:ssid payload))
		(contains? payload :password) (assoc :password (:password payload))
		(contains? payload :metadata) (assoc :metadata (:metadata payload))
		(or (contains? payload :energy-current)
				(contains? payload :energy-max))
		(assoc :energy {:current (double (or (:energy-current payload)
																				 (get-in network [:energy :current] 0.0)))
										:max (double (or (:energy-max payload)
																		 (get-in network [:energy :max] 0.0)))})
		(contains? payload :node-count)
		(assoc :topology {:node-count (:node-count payload)})))