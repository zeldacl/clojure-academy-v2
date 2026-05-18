(ns cn.li.ac.wireless.data.network-mutation
	"Mutable operations for wireless network metadata."
	(:require [cn.li.ac.wireless.data.network-state :as network-state]
					[cn.li.mcmod.util.log :as log]))

(defn reset-password!
	"Change network password"
	[network new-password]
	(network-state/set-state-value! network :password new-password)
	(log/info (format "Network '%s' password changed" (network-state/get-ssid network)))
	true)

(defn reset-ssid!
	"Change network ssid"
	[network new-ssid]
	(network-state/set-state-value! network :ssid new-ssid)
	(log/info (format "Network ssid changed to '%s'" new-ssid))
	true)
