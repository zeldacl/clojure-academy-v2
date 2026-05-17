(ns cn.li.ac.block.wireless-matrix.network-infra
	"Infrastructure access for wireless matrix GUI handlers."
	(:require [cn.li.mcmod.platform.entity :as entity]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.ac.wireless.core.capability-resolver :as resolver]
						[cn.li.ac.wireless.api :as wireless-api]
						[cn.li.ac.block.wireless-matrix.logic :as matrix-logic])
	(:import [cn.li.acapi.wireless IWirelessMatrix]))

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
				(resolver/matrix-capability ctrl)))))

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
		(wireless-api/get-wireless-net-by-matrix ctrl)))

(defn create-network!
	[ctrl ssid password]
	(boolean (wireless-api/create-network! ctrl ssid password)))

(defn change-ssid!
	[network new-ssid]
	(wireless-api/change-network-ssid! network new-ssid))

(defn change-password!
	[network new-password]
	(wireless-api/change-network-password! network new-password))