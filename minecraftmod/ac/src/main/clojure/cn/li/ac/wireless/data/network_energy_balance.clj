(ns cn.li.ac.wireless.data.network-energy-balance
	(:require [cn.li.ac.wireless.core.vblock :as vb])
	(:import [cn.li.acapi.wireless IWirelessMatrix IWirelessNode]))

(defn- get-matrix-bandwidth
	[network]
	(let [world (:world (:world-data network))
				matrix-vb (:matrix network)]
		(if-let [matrix (vb/vblock-get matrix-vb world)]
			(double (.getMatrixBandwidth ^IWirelessMatrix matrix))
			0.0)))

(defn- active-nodes
	[network]
	(let [world (:world (:world-data network))]
		(->> @(:nodes network)
				 (map (fn [node-vb]
								(when-let [node (vb/vblock-get node-vb world)]
									[node-vb node])))
				 (remove nil?)
				 vec)))

(defn balance-energy!
	"Balance energy across linked wireless nodes toward the current mean.
	The adjustment is bandwidth-limited by matrix bandwidth per tick."
	[network]
	(let [pairs (active-nodes network)
				nodes (mapv second pairs)
				n (count nodes)]
		(when (pos? n)
			(let [energies (mapv (fn [^IWirelessNode node] (double (.getEnergy node))) nodes)
						avg (/ (reduce + energies) (double n))
						total-need (reduce + (map (fn [e] (max 0.0 (- avg e))) energies))
						max-transfer (get-matrix-bandwidth network)
						scale (if (pos? total-need)
										(min 1.0 (/ max-transfer total-need))
										0.0)]
				(doseq [^IWirelessNode node nodes]
					(let [e (double (.getEnergy node))
								delta (* (- avg e) scale)
								next-e (-> (+ e delta)
													 (max 0.0)
													 (min (double (.getMaxEnergy node))))]
						(.setEnergy node next-e)))))))