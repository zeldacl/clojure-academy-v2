(ns cn.li.ac.wireless.data.network-state
	"State model and basic accessors for wireless networks."
	(:require [cn.li.ac.wireless.core.vblock :as vb]))

(defrecord WirelessNet
	[world-data
	 matrix
	 ssid
	 password
	 nodes
	 to-remove-nodes
	 buffer
	 update-counter
	 disposed])

(defn create-wireless-net
	"Create a new wireless network"
	[world-data matrix-vblock ssid password]
	(->WirelessNet
		world-data
		matrix-vblock
		(atom ssid)
		(atom password)
		(atom [])
		(atom [])
		(atom 0.0)
		(atom 0)
		(atom false)))

(defn get-matrix
	"Get the matrix TileEntity"
	[network]
	(vb/vblock-get (:matrix network) (:world (:world-data network))))

(defn is-disposed? [network] @(:disposed network))
(defn get-ssid [network] @(:ssid network))
(defn get-password [network] @(:password network))
(defn get-load [network] (count @(:nodes network)))

(defn get-capacity
	"Get network capacity from matrix"
	[network]
	(if-let [matrix (get-matrix network)]
		(.getMatrixCapacity ^cn.li.acapi.wireless.IWirelessMatrix matrix)
		0))