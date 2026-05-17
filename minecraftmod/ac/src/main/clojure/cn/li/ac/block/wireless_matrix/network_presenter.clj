(ns cn.li.ac.block.wireless-matrix.network-presenter
	"Response DTO builders for wireless matrix GUI handlers."
	(:require [cn.li.ac.wireless.api :as wireless-api])
	(:import [cn.li.acapi.wireless IWirelessMatrix]))

(defn gather-info-response
	[network cap]
	(let [{:keys [ssid password load]} (wireless-api/network-snapshot network)]
		{:ssid ssid
		 :password password
		 :owner (if cap (str (.getPlacerName ^IWirelessMatrix cap)) "Unknown")
		 :load (or load 0)
		 :max-capacity (if cap (.getMatrixCapacity ^IWirelessMatrix cap) 16)
		 :range (if cap (.getMatrixRange ^IWirelessMatrix cap) 64.0)
		 :bandwidth (if cap (.getMatrixBandwidth ^IWirelessMatrix cap) 100)
		 :initialized (boolean network)}))