(ns cn.li.ac.wireless.logic.frequency-manager
	"Frequency/SSID-oriented queries for wireless networks."
	(:require [clojure.string :as str]
						[cn.li.ac.wireless.service.network-manager :as network-manager]))

(defn normalize-frequency
	[value]
	(some-> value str str/trim))

(defn frequency-match?
	[network frequency]
	(= (normalize-frequency (:ssid network))
		 (normalize-frequency frequency)))

(defn list-networks-on-frequency
	[registry frequency]
	(let [target (normalize-frequency frequency)]
		(filterv #(frequency-match? % target)
						 (network-manager/get-all-networks registry))))

(defn first-network-on-frequency
	[registry frequency]
	(first (list-networks-on-frequency registry frequency)))