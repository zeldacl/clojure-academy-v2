(ns cn.li.ac.block.wireless-node.network-presenter
	"Response DTO builders for wireless node GUI network handlers."
	(:require [cn.li.ac.wireless.service.network-command :as network-command]))

(defn linked->dto
	[linked]
	(when linked
		{:ssid (:ssid linked)
		 :is-encrypted? (not (empty? (str (:password linked))))}))

(defn available-net->dto
	[net matrix-cap {:keys [matrix-capacity matrix-bandwidth matrix-range]}]
	{:ssid (:ssid net)
	 :is-encrypted? (not (empty? (str (:password net))))
	 :load (network-command/network-load net)
	 :capacity (matrix-capacity matrix-cap)
	 :bandwidth (matrix-bandwidth matrix-cap)
	 :range (matrix-range matrix-cap)})

(defn list-networks-response
	[{:keys [linked avail linked-ssid matrix-cap-fn matrix-capacity matrix-bandwidth matrix-range]}]
	{:linked (linked->dto linked)
	 :avail (->> avail
					(remove (fn [net] (= (:ssid net) linked-ssid)))
					(mapv (fn [net]
								(let [matrix-cap (matrix-cap-fn net)]
									(available-net->dto
										net
										matrix-cap
										{:matrix-capacity matrix-capacity
										 :matrix-bandwidth matrix-bandwidth
										 :matrix-range matrix-range})))))} )