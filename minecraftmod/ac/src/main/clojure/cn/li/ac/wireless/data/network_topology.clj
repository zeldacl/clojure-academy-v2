(ns cn.li.ac.wireless.data.network-topology
	"Explicit topology facade over the wireless network domain/service layers."
	(:require [cn.li.ac.wireless.domain.network :as domain-network]
						[cn.li.ac.wireless.service.network-manager :as network-manager]))

(def create-network domain-network/create-network)
(def valid-network? domain-network/valid-network?)
(def validate-network domain-network/validate-network)
(def add-node domain-network/add-node)
(def remove-node domain-network/remove-node)
(def contains-node? domain-network/contains-node?)
(def get-node-count domain-network/get-node-count)
(def network-summary domain-network/network-summary)

(def create-network-registry network-manager/create-network-registry)
(def create-network! network-manager/create-network!)
(def get-network network-manager/get-network)
(def get-networks-by-ssid network-manager/get-networks-by-ssid)
(def add-node-to-network! network-manager/add-node-to-network!)
(def remove-node-from-network! network-manager/remove-node-from-network!)
(def get-all-networks network-manager/get-all-networks)
(def get-network-count network-manager/get-network-count)