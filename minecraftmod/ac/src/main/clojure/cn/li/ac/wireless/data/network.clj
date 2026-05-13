(ns cn.li.ac.wireless.data.network
	"Wireless network compatibility facade.

	Responsibilities are split across:
	- network-state: record + accessors
	- network-membership: node add/remove + cleanup
	- network-validation: validity/range/dispose
	- network-energy-balance: balancing algorithm"
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.network-config :as network-config]
						[cn.li.ac.wireless.data.network-state :as state]
						[cn.li.ac.wireless.data.network-membership :as membership]
						[cn.li.ac.wireless.data.network-validation :as validation]
						[cn.li.ac.wireless.data.network-energy-balance :as energy-balance]
						[cn.li.mcmod.platform.nbt :as nbt]
						[cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Backward-compatible public API
;; ============================================================================

(def create-wireless-net state/create-wireless-net)
(def get-matrix state/get-matrix)
(def is-disposed? state/is-disposed?)
(def get-ssid state/get-ssid)
(def get-password state/get-password)
(def get-load state/get-load)
(def get-capacity state/get-capacity)

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

(def add-node! membership/add-node!)
(def remove-node! membership/remove-node!)
(def validate! validation/validate!)
(def is-in-range? validation/is-in-range?)
(def dispose! validation/dispose!)

(defn tick-wireless-net!
	"Tick the wireless network"
	[network]
	(when-not @(:disposed network)
		(when (validate! network)
			(swap! (:update-counter network) inc)
			(when (>= @(:update-counter network) (network-config/update-interval-ticks))
				(reset! (:update-counter network) 0)
				(energy-balance/balance-energy! network))
			(membership/cleanup-removed-nodes! network))))

;; ============================================================================
;; NBT Serialization
;; ============================================================================

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
				net (create-wireless-net world-data matrix ssid password)]
		(reset! (:buffer net) buffer)
		(reset! (:nodes net) nodes)
		net))

(defn print-network-info
	"Print network information"
	[network]
	(log/info (format "=== Network: %s ===" (:ssid network)))
	(log/info (format "  Load: %d/%d" (get-load network) (get-capacity network)))
	(log/info (format "  Buffer: %.1f/%.1f" @(:buffer network) (network-config/buffer-max)))
	(log/info (format "  Nodes: %d" (count @(:nodes network))))
	(log/info (format "  Disposed: %s" @(:disposed network))))

(defn init-wireless-network! []
	(log/info "Wireless network system initialized"))