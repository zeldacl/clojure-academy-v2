(ns cn.li.ac.wireless.data.network-energy-balance
	(:require [cn.li.ac.wireless.core.capability-resolver :as resolver]
					[cn.li.ac.wireless.data.network-state :as network-state])
	(:import [cn.li.acapi.wireless IWirelessMatrix IWirelessNode]))

(defn- get-matrix-bandwidth
	[network]
	(let [world (:world (:world-data network))
				matrix-vb (:matrix network)]
		(if-let [matrix (resolver/resolve-matrix-cap world matrix-vb)]
			(double (.getMatrixBandwidth ^IWirelessMatrix matrix))
			0.0)))

(defn- active-nodes
	[network]
	(let [world (:world (:world-data network))]
		(->> (network-state/get-nodes network)
				 (map (fn [node-vb]
								(when-let [node (resolver/resolve-node-cap world node-vb)]
									[node-vb node])))
				 (remove nil?)
				 vec)))

(defn- water-fill-level
	"Find a shared target level where each node is capped by its max energy."
	[total-energy capacities]
	(let [max-capacity (if (seq capacities)
							 (double (reduce max capacities))
							 0.0)]
		(if (or (not (pos? total-energy)) (not (seq capacities)) (zero? max-capacity))
			0.0
			(loop [low 0.0
					 high max-capacity
					 remaining 64]
				(if (zero? remaining)
					high
					(let [mid (/ (+ low high) 2.0)
							filled (reduce + (map #(min (double %) mid) capacities))]
						(if (< filled total-energy)
							(recur mid high (dec remaining))
							(recur low mid (dec remaining)))))))))

(defn- target-energies
	"Compute capacity-aware balancing targets while conserving total energy."
	[energies capacities]
	(let [safe-capacities (mapv #(max 0.0 (double %)) capacities)
			clamped-energies (mapv (fn [energy capacity]
										 (-> (double energy)
											 (max 0.0)
											 (min capacity)))
									 energies safe-capacities)
			total-energy (min (reduce + clamped-energies)
							  (reduce + safe-capacities))
			level (water-fill-level total-energy safe-capacities)]
		(mapv #(min % level) safe-capacities)))

(defn balance-energy!
	"Balance energy across linked wireless nodes toward the current mean.
	The adjustment is bandwidth-limited by matrix bandwidth per tick."
	[network]
	(let [pairs (active-nodes network)
				nodes (mapv second pairs)
				n (count nodes)]
		(when (pos? n)
			(let [energies (mapv (fn [^IWirelessNode node] (double (.getEnergy node))) nodes)
					capacities (mapv (fn [^IWirelessNode node] (double (.getMaxEnergy node))) nodes)
					targets (target-energies energies capacities)
					total-need (reduce + (map (fn [energy target]
													 (max 0.0 (- target energy)))
												 energies targets))
						max-transfer (get-matrix-bandwidth network)
						scale (if (pos? total-need)
										(min 1.0 (/ max-transfer total-need))
										0.0)]
				(doseq [[^IWirelessNode node target] (map vector nodes targets)]
					(let [e (double (.getEnergy node))
							delta (* (- target e) scale)
								next-e (-> (+ e delta)
													 (max 0.0)
													 (min (double (.getMaxEnergy node))))]
						(.setEnergy node next-e)))))))