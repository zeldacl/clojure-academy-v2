(ns cn.li.ac.wireless.data.network-membership
	"Node membership operations for wireless networks."
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.network-state :as net-state]
						[cn.li.ac.wireless.data.spatial-lookup :as spatial]
						[cn.li.ac.wireless.data.world-registry :as world-registry]
						[cn.li.mcmod.util.log :as log]))

(defn- find-existing-network-by-node
	[world-data node-vblock]
	(get @(:net-lookup world-data) node-vblock))

(defn- password-valid?
	[network password-attempt]
	(= password-attempt (net-state/get-password network)))

(defn- has-capacity?
	[network]
	(< (net-state/get-load network) (net-state/get-capacity network)))

(defn- matrix-in-range?
	[network matrix node-vblock]
	(let [range (.getMatrixRange ^cn.li.acapi.wireless.IWirelessMatrix matrix)
				dist-sq (vb/dist-sq node-vblock (:matrix network))]
		(<= dist-sq (* range range))))

(defn remove-node!
	"Remove a node from the network immediately."
	[network node-vblock]
	(world-registry/transact!
		(:world-data network)
		(fn [_]
			(let [nodes-before (net-state/get-nodes network)
						removed? (boolean (some #(vb/vblock-equals? % node-vblock) nodes-before))]
				(when removed?
					(swap! (:nodes network)
								 (fn [nodes]
									 (filterv #(not (vb/vblock-equals? % node-vblock)) nodes)))
					(swap! (:net-lookup (:world-data network)) dissoc node-vblock)
					(spatial/remove-from-spatial-index! (:world-data network) node-vblock)
					(log/info (format "Removed node %s from '%s'"
														(vb/vblock-to-string node-vblock)
														(net-state/get-ssid network))))
				removed?))))

(defn- remove-node-from-old-network!
	[network node-vblock]
	(let [world-data (:world-data network)
				old-net (find-existing-network-by-node world-data node-vblock)]
		(when old-net
			(remove-node! old-net node-vblock))))

(defn- attach-node!
	[network node-vblock]
	(world-registry/transact!
		(:world-data network)
		(fn [_]
			(remove-node-from-old-network! network node-vblock)
			(swap! (:nodes network) conj node-vblock)
			(swap! (:net-lookup (:world-data network)) assoc node-vblock network)
			(log/info (format "Added node %s to network '%s'"
												(vb/vblock-to-string node-vblock)
												(net-state/get-ssid network)))
			true)))

(defn add-node!
	"Add a node to the network.
	Returns true if successful, false otherwise."
	[network node-vblock password-attempt]
	(cond
		(not (password-valid? network password-attempt))
		(do
			(log/info (format "Node add failed: incorrect password for '%s'" (net-state/get-ssid network)))
			false)

		(not (has-capacity? network))
		(do
			(log/info (format "Node add failed: network '%s' at capacity" (net-state/get-ssid network)))
			false)

		:else
		(let [matrix (net-state/get-matrix network)]
			(if-not matrix
				(do
					(log/info "Node add failed: matrix not found")
					false)
				(if-not (matrix-in-range? network matrix node-vblock)
					(let [range (.getMatrixRange ^cn.li.acapi.wireless.IWirelessMatrix matrix)
								dist-sq (vb/dist-sq node-vblock (:matrix network))]
						(log/info (format "Node add failed: out of range (%.1f > %.1f)"
															(Math/sqrt dist-sq) range))
						false)
					(attach-node! network node-vblock))))))