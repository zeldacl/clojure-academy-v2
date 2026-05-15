(ns cn.li.ac.wireless.data.network-serialization
	"Compatibility wrapper for wireless network NBT serialization.

	Real serialization logic is centralized in
	`cn.li.ac.wireless.persistence.nbt-codec`.
	This namespace adapts legacy mutable network records to the new
	domain model for callers that still use `wireless.data.*`."
	(:require [cn.li.ac.foundation.vblock :as fvb]
						[cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.network-config :as network-config]
						[cn.li.ac.wireless.data.network-state :as state]
						[cn.li.ac.wireless.domain.energy :as domain-energy]
						[cn.li.ac.wireless.domain.network :as domain-net]
						[cn.li.ac.wireless.persistence.nbt-codec :as codec]
						[cn.li.mcmod.util.log :as log]))

(defn- legacy-vblock->domain
	[vblock]
	(fvb/vblock (:x vblock)
						(:y vblock)
						(:z vblock)
						(:block-type vblock)
						(:ignore-chunk vblock)))

(defn- domain-vblock->legacy
	[vblock]
	(vb/->VBlock (:x vblock)
						(:y vblock)
						(:z vblock)
						(:block-type vblock)
						(:ignore-chunk vblock)))

(defn- legacy-network-id
	[network]
	(let [matrix (:matrix network)
				ssid @(:ssid network)]
		(keyword (str "legacy-"
								(:x matrix) "-" (:y matrix) "-" (:z matrix)
								"-" (Math/abs (hash ssid))))))

(defn- legacy-network->domain
	[network]
	(let [buffer @(:buffer network)
				capacity (double (max 1 (state/get-capacity network)))
				now (System/currentTimeMillis)]
		(domain-net/->Network
			(legacy-network-id network)
			@(:ssid network)
			@(:password network)
			(legacy-vblock->domain (:matrix network))
			(mapv legacy-vblock->domain @(:nodes network))
			(domain-energy/->EnergyContainer buffer capacity 0.0 1.0 now)
			now
			now
			{})))

(defn- domain-network->legacy
	[world-data network]
	(let [legacy (state/create-wireless-net world-data
																(domain-vblock->legacy (:matrix-vblock network))
																(:ssid network)
																(:password network))
				nodes (mapv domain-vblock->legacy (:nodes network))
				energy (or (:energy network) {:current 0.0})]
		(reset! (:buffer legacy) (double (:current energy 0.0)))
		(reset! (:nodes legacy) nodes)
		legacy))

(defn network-to-nbt
	"Serialize legacy wireless network using canonical persistence codec."
	[network]
	(codec/network-to-nbt (legacy-network->domain network)))

(defn network-from-nbt
	"Deserialize legacy wireless network via canonical persistence codec."
	[world-data nbt-compound]
	(when-let [domain-network (codec/network-from-nbt nbt-compound)]
		(domain-network->legacy world-data domain-network)))

(defn print-network-info
	"Print network information"
	[network]
	(log/info (format "=== Network: %s ===" (:ssid network)))
	(log/info (format "  Load: %d/%d" (state/get-load network) (state/get-capacity network)))
	(log/info (format "  Buffer: %.1f/%.1f" @(:buffer network) (network-config/buffer-max)))
	(log/info (format "  Nodes: %d" (count @(:nodes network))))
	(log/info (format "  Disposed: %s" @(:disposed network))))
