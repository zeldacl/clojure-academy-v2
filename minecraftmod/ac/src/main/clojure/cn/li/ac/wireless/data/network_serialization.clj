(ns cn.li.ac.wireless.data.network-serialization
	"NBT serialization for wireless network records."
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.network-config :as network-config]
						[cn.li.ac.wireless.data.network-state :as state]
						[cn.li.mcmod.platform.nbt :as nbt]
						[cn.li.mcmod.util.log :as log]))

(defn network-to-nbt
	"Serialize network to NBT."
	[network]
	(let [nbt-compound (nbt/create-nbt-compound)
				list-obj (nbt/create-nbt-list)
				world (:world (:world-data network))]
		(nbt/nbt-set-tag! nbt-compound "matrix" (vb/vblock-to-nbt (:matrix network)))
		(nbt/nbt-set-string! nbt-compound "ssid" @(:ssid network))
		(nbt/nbt-set-string! nbt-compound "password" @(:password network))
		(nbt/nbt-set-double! nbt-compound "buffer" @(:buffer network))
		(doseq [node-vb @(:nodes network)]
			(when (or (not (vb/is-chunk-loaded? node-vb world))
								(vb/vblock-get node-vb world))
				(nbt/nbt-append! list-obj (vb/vblock-to-nbt node-vb))))
		(nbt/nbt-set-tag! nbt-compound "list" list-obj)
		nbt-compound))

(defn network-from-nbt
	"Deserialize network from NBT."
	[world-data nbt-compound]
	(let [matrix (vb/vblock-from-nbt (nbt/nbt-get-compound nbt-compound "matrix"))
				ssid (nbt/nbt-get-string nbt-compound "ssid")
				password (nbt/nbt-get-string nbt-compound "password")
				buffer (nbt/nbt-get-double nbt-compound "buffer")
				list-obj (nbt/nbt-get-list nbt-compound "list")
				size (nbt/nbt-list-size list-obj)
				nodes (vec (for [i (range size)]
										 (vb/vblock-from-nbt (nbt/nbt-list-get-compound list-obj i))))
				net (state/create-wireless-net world-data matrix ssid password)]
		(reset! (:buffer net) buffer)
		(reset! (:nodes net) nodes)
		net))

(defn print-network-info
	"Print network information"
	[network]
	(log/info (format "=== Network: %s ===" (:ssid network)))
	(log/info (format "  Load: %d/%d" (state/get-load network) (state/get-capacity network)))
	(log/info (format "  Buffer: %.1f/%.1f" @(:buffer network) (network-config/buffer-max)))
	(log/info (format "  Nodes: %d" (count @(:nodes network))))
	(log/info (format "  Disposed: %s" @(:disposed network))))
