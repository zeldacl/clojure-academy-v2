(ns cn.li.ac.wireless.data.network-validation
	"Validation and disposal operations for wireless networks."
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.core.capability-resolver :as resolver]
						[cn.li.ac.wireless.data.network-state :as net-state]
						[cn.li.mcmod.util.log :as log]))

(defn validate!
	"Validate network integrity.
	Returns true if valid, false if should be disposed."
	[network]
	(let [world (:world (:world-data network))
	      matrix-vb (:matrix network)
	      network (if (and (vb/is-chunk-loaded? matrix-vb world)
	                       (not (resolver/resolve-matrix-cap world matrix-vb)))
	                (do
	                  (log/info (format "Network '%s' disposed: matrix destroyed"
	                                    (net-state/get-ssid network)))
	                  (net-state/mark-disposed! network))
	                network)]
	  (net-state/active? network)))

(defn is-in-range?
	"Check if coordinates are in network range."
	[network x y z]
	(if-let [matrix (net-state/get-matrix network)]
		(let [range (.getMatrixRange ^cn.li.acapi.wireless.IWirelessMatrix matrix)
					dist-sq (vb/dist-sq-pos (:matrix network) x y z)]
			(<= dist-sq (* range range)))
		false))

(defn dispose!
	"Dispose the network and unlink all nodes."
	[network]
	(net-state/mark-disposed! network)
	(log/info (format "Network '%s' disposed" (net-state/get-ssid network))))