(ns cn.li.ac.block.wireless-node.network-presenter
	"Response DTO builders for wireless node GUI network handlers."
	(:require [cn.li.ac.wireless.api :as wireless-api]))

(defn linked->dto
	[linked]
	(when linked
		(let [{:keys [ssid password]} (wireless-api/network-snapshot linked)]
			{:ssid ssid
			 :is-encrypted? (not (empty? (str password)))})))

(defn available-net->dto
	[net matrix-cap {:keys [matrix-capacity matrix-bandwidth matrix-range]}]
	(let [{:keys [ssid password load]} (wireless-api/network-snapshot net)]
		{:ssid ssid
		 :is-encrypted? (not (empty? (str password)))
		 :load load
		 :capacity (matrix-capacity matrix-cap)
		 :bandwidth (matrix-bandwidth matrix-cap)
		 :range (matrix-range matrix-cap)}))

(defn list-networks-response
	[{:keys [linked avail linked-ssid matrix-cap-fn matrix-capacity matrix-bandwidth matrix-range]}]
	{:linked (linked->dto linked)
	 :avail (->> avail
					(remove (fn [net] (= (wireless-api/network-ssid net) linked-ssid)))
					(mapv (fn [net]
								(let [matrix-cap (matrix-cap-fn net)]
									(available-net->dto
										net
										matrix-cap
										{:matrix-capacity matrix-capacity
										 :matrix-bandwidth matrix-bandwidth
										 :matrix-range matrix-range})))))} )