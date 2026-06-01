(ns cn.li.ac.wireless.data.network-mutation
	"Mutable operations for wireless network metadata."
	(:require [cn.li.ac.wireless.data.network-state :as network-state]
					[cn.li.mcmod.util.log :as log]))

(defn reset-password!
	"Change network password. Returns updated network."
	[network new-password]
	(let [updated (network-state/set-state-value! network :password new-password)]
		(log/info (format "Network '%s' password changed" (network-state/get-ssid updated)))
		updated))

(defn reset-ssid!
	"Change network ssid. Returns updated network."
	[network new-ssid]
	(let [updated (network-state/set-state-value! network :ssid new-ssid)]
		(log/info (format "Network ssid changed to '%s'" new-ssid))
		updated))
