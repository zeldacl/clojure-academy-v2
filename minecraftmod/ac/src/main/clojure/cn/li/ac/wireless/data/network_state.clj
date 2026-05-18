(ns cn.li.ac.wireless.data.network-state
	"State model and basic accessors for wireless networks."
	(:require [cn.li.ac.wireless.core.capability-resolver :as resolver])
	(:import [clojure.lang IDeref]))

(defrecord WirelessNet
	[world-data
	 matrix
	 state])

(defn create-wireless-net
	"Create a new wireless network"
	[world-data matrix-vblock ssid password]
	(->WirelessNet
		world-data
		matrix-vblock
		(atom {:ssid ssid
					 :password password
					 :nodes []
					 :buffer 0.0
					 :update-counter 0
					 :disposed false})))

(defn get-matrix
	"Get the matrix TileEntity"
	[network]
	(resolver/resolve-matrix-cap (:world (:world-data network)) (:matrix network)))

(defn field-value
	"Return the plain value behind a mutable field."
	[value]
	(if (instance? IDeref value) @value value))

(defn state-value
	[network key]
	(get @(:state network) key))

(defn set-state-value!
	[network key value]
	(swap! (:state network) assoc key value)
	value)

(defn update-state-value!
	[network key f & args]
	(apply swap! (:state network) update key f args)
	(state-value network key))

(defn is-disposed? [network] (boolean (state-value network :disposed)))
(defn get-ssid [network] (state-value network :ssid))
(defn get-password [network] (state-value network :password))
(defn get-nodes [network] (vec (or (state-value network :nodes) [])))
(defn get-load [network] (count (get-nodes network)))

(defn get-buffer [network] (state-value network :buffer))
(defn get-update-counter [network] (state-value network :update-counter))
(defn set-nodes! [network nodes] (set-state-value! network :nodes (vec nodes)))
(defn update-nodes! [network f & args] (apply update-state-value! network :nodes f args))
(defn set-buffer! [network value] (set-state-value! network :buffer value))
(defn set-update-counter! [network value] (set-state-value! network :update-counter value))
(defn increment-update-counter! [network] (update-state-value! network :update-counter (fnil inc 0)))
(defn mark-disposed! [network] (set-state-value! network :disposed true))

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