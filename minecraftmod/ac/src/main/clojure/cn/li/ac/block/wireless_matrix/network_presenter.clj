(ns cn.li.ac.block.wireless-matrix.network-presenter
	"Response DTO builders for wireless matrix GUI handlers."
	(:require [cn.li.ac.wireless.service.network-command :as network-command])
	(:import [cn.li.acapi.wireless IWirelessMatrix]))

(defn gather-info-response
	[network cap]
	{:ssid (when network (:ssid network))
	 :password (when network (:password network))
	 :owner (if cap (str (.getPlacerName ^IWirelessMatrix cap)) "Unknown")
	 :load (if network (network-command/network-load network) 0)
	 :max-capacity (if cap (.getMatrixCapacity ^IWirelessMatrix cap) 16)
	 :range (if cap (.getMatrixRange ^IWirelessMatrix cap) 64.0)
	 :bandwidth (if cap (.getMatrixBandwidth ^IWirelessMatrix cap) 100)
	 :initialized (boolean network)})