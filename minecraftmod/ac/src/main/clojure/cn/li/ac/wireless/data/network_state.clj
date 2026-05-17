(ns cn.li.ac.wireless.data.network-state
	"State model and basic accessors for wireless networks."
	(:require [cn.li.ac.wireless.core.capability-resolver :as resolver])
	(:import [clojure.lang IDeref]))

(defrecord WirelessNet
	[world-data
	 matrix
	 ssid
	 password
	 nodes
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
		(atom 0.0)
		(atom 0)
		(atom false)))

(defn get-matrix
	"Get the matrix TileEntity"
	[network]
	(resolver/resolve-matrix-cap (:world (:world-data network)) (:matrix network)))

(defn field-value
	"Return the plain value behind a mutable field."
	[value]
	(if (instance? IDeref value) @value value))

(defn is-disposed? [network] (boolean (field-value (:disposed network))))
(defn get-ssid [network] (field-value (:ssid network)))
(defn get-password [network] (field-value (:password network)))
(defn get-nodes [network] (vec (or (field-value (:nodes network)) [])))
(defn get-load [network] (count (get-nodes network)))

(defn active?
	[network]
	(boolean (and network (not (is-disposed? network)))))

(defn snapshot
	"Return a read-only, plain-value view of a wireless network."
	[network]
	(when network
		{:matrix (:matrix network)
		 :ssid (get-ssid network)
		 :password (get-password network)
		 :nodes (get-nodes network)
		 :load (get-load network)
		 :disposed? (is-disposed? network)}))

(defn get-capacity
	"Get network capacity from matrix"
	[network]
	(if-let [matrix (get-matrix network)]
		(.getMatrixCapacity ^cn.li.acapi.wireless.IWirelessMatrix matrix)
		0))