(ns cn.li.ac.wireless.data.network-mutation
	"Mutable operations for wireless network metadata."
	(:require [cn.li.mcmod.util.log :as log]))

(defn reset-password!
	"Change network password"
	[network new-password]
	(reset! (:password network) new-password)
	(log/info (format "Network '%s' password changed" @(:ssid network)))
	true)

(defn reset-ssid!
	"Change network ssid"
	[network new-ssid]
	(reset! (:ssid network) new-ssid)
	(log/info (format "Network ssid changed to '%s'" new-ssid))
	true)
