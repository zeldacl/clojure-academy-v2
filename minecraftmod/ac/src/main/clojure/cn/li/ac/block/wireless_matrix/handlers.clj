(ns cn.li.ac.block.wireless-matrix.handlers
	"Wireless Matrix network handlers."
	(:require [cn.li.mcmod.network.server :as net-server]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.entity :as entity]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.ac.wireless.api :as helper]
						[cn.li.ac.wireless.data.network :as wireless-net]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
						[cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessMatrix WirelessCapabilityKeys]))

(defn- msg [action]
	(msg-registry/msg :matrix action))

(defn- get-wireless-network [tile]
	(helper/get-wireless-net-by-matrix tile))

(defn- matrix-wireless-cap [be]
	(when be
		(let [ctrl (matrix-logic/resolve-controller-be be)]
			(when ctrl
				(or (platform-be/get-capability ctrl WirelessCapabilityKeys/MATRIX)
						(when (instance? IWirelessMatrix ctrl) ctrl))))))

(defn- is-owner? [^IWirelessMatrix matrix-cap player]
	(= (str (.getPlacerName matrix-cap))
		 (str (entity/player-get-name player))))

(defn- with-owner-tile [payload player f]
	(let [world (net-helpers/get-world player)
				be (net-helpers/get-tile-at world payload)
				ctrl (when be (matrix-logic/resolve-controller-be be))
				cap (when be (matrix-wireless-cap be))]
		(if (and ctrl cap (is-owner? cap player))
			(f ctrl)
			{:success false})))

(defn handle-gather-info [payload player]
	(let [be (net-helpers/get-tile-at (net-helpers/get-world player) payload)
				ctrl (when be (matrix-logic/resolve-controller-be be))
				cap (when be (matrix-wireless-cap be))
				network (when ctrl (get-wireless-network ctrl))]
		(if network
			{:ssid (:ssid network)
			 :password (:password network)
			 :owner (if cap (str (.getPlacerName ^IWirelessMatrix cap)) "Unknown")
			 :load (wireless-net/get-load network)
			 :max-capacity (if cap (.getMatrixCapacity ^IWirelessMatrix cap) 16)
			 :range (if cap (.getMatrixRange ^IWirelessMatrix cap) 64.0)
			 :bandwidth (if cap (.getMatrixBandwidth ^IWirelessMatrix cap) 100)
			 :initialized true}
			{:ssid nil
			 :password nil
			 :owner (if cap (str (.getPlacerName ^IWirelessMatrix cap)) "Unknown")
			 :load 0
			 :max-capacity (if cap (.getMatrixCapacity ^IWirelessMatrix cap) 16)
			 :range (if cap (.getMatrixRange ^IWirelessMatrix cap) 64.0)
			 :bandwidth (if cap (.getMatrixBandwidth ^IWirelessMatrix cap) 100)
			 :initialized false})))

(defn handle-init-network [payload player]
	(with-owner-tile payload player
		(fn [tile]
			(let [{:keys [ssid password]} payload]
				(try
					{:success (boolean (helper/create-network! tile ssid password))}
					(catch Exception e
						(log/error "Failed to initialize network:" (ex-message e))
						{:success false}))))))

(defn handle-change-ssid [payload player]
	(with-owner-tile payload player
		(fn [tile]
			(if-let [network (get-wireless-network tile)]
				(try
					(let [old-ssid (:ssid network)
								new-ssid (:new-ssid payload)]
						(wireless-net/reset-ssid! network new-ssid)
						(swap! (:net-lookup (:world-data network)) dissoc old-ssid)
						(swap! (:net-lookup (:world-data network)) assoc new-ssid network)
						{:success true})
					(catch Exception e
						(log/error "Failed to change SSID:" (ex-message e))
						{:success false}))
				{:success false}))))

(defn handle-change-password [payload player]
	(with-owner-tile payload player
		(fn [tile]
			(if-let [network (get-wireless-network tile)]
				(try
					(wireless-net/reset-password! network (:new-password payload))
					{:success true}
					(catch Exception e
						(log/error "Failed to change password:" (ex-message e))
						{:success false}))
				{:success false}))))

(defn register-network-handlers! []
	(net-server/register-handler (msg :gather-info) handle-gather-info)
	(net-server/register-handler (msg :init) handle-init-network)
	(net-server/register-handler (msg :change-ssid) handle-change-ssid)
	(net-server/register-handler (msg :change-password) handle-change-password)
	(log/info "Matrix network handlers registered"))