(ns cn.li.ac.block.wireless-matrix.network-infra
	"Infrastructure access for wireless matrix GUI handlers."
	(:require [cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.entity :as entity]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.ac.wireless.api-query :as wireless-query]
						[cn.li.ac.wireless.api-command :as wireless-command]
						[cn.li.ac.wireless.service.network-command :as network-command]
						[cn.li.ac.block.wireless-matrix.logic :as matrix-logic])
	(:import [cn.li.acapi.wireless IWirelessMatrix WirelessCapabilityKeys]))

(defn resolve-world-tile
	[payload player]
	(let [world (net-helpers/get-world player)
				be (net-helpers/get-tile-at world payload)]
		{:world world :be be}))

(defn resolve-controller
	[be]
	(when be
		(matrix-logic/resolve-controller-be be)))

(defn matrix-wireless-cap
	[be]
	(when be
		(let [ctrl (matrix-logic/resolve-controller-be be)]
			(when ctrl
				(or (platform-be/get-capability ctrl WirelessCapabilityKeys/MATRIX)
						(when (instance? IWirelessMatrix ctrl) ctrl))))))

(defn owner?
	[^IWirelessMatrix matrix-cap player]
	(= (str (.getPlacerName matrix-cap))
		 (str (entity/player-get-name player))))

(defn owner-controller
	[payload player]
	(let [{:keys [be]} (resolve-world-tile payload player)
				ctrl (resolve-controller be)
				cap (matrix-wireless-cap be)]
		(when (and ctrl cap (owner? cap player))
			{:ctrl ctrl :cap cap})))

(defn wireless-network
	[ctrl]
	(when ctrl
		(wireless-query/get-wireless-net-by-matrix ctrl)))

(defn create-network!
	[ctrl ssid password]
	(boolean (wireless-command/create-network! ctrl ssid password)))

(defn change-ssid!
	[network new-ssid]
	(let [old-ssid (:ssid network)]
		(network-command/reset-network-ssid! network new-ssid)
		(network-command/refresh-world-ssid-lookup! network old-ssid new-ssid)
		true))

(defn change-password!
	[network new-password]
	(network-command/reset-network-password! network new-password)
	true)