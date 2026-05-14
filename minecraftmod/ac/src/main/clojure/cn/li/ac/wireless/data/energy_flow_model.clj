(ns cn.li.ac.wireless.data.energy-flow-model
	"Explicit wireless energy-flow facade over pure and service-level helpers."
	(:require [cn.li.ac.wireless.domain.energy :as domain-energy]
						[cn.li.ac.wireless.domain.network :as domain-network]
						[cn.li.ac.wireless.service.network-manager :as network-manager]))

(def create-energy-container domain-energy/create-energy-container)
(def receive-energy domain-energy/receive-energy)
(def extract-energy domain-energy/extract-energy)
(def set-energy domain-energy/set-energy)
(def transfer-energy domain-energy/transfer-energy)
(def balance-containers domain-energy/balance-containers)
(def redistribute-energy domain-energy/redistribute-energy)
(def get-current-energy domain-energy/get-current-energy)
(def get-max-energy domain-energy/get-max-energy)
(def get-energy-percent domain-energy/get-energy-percent)

(def get-network-energy domain-network/get-energy)
(def get-network-capacity domain-network/get-max-energy)
(def set-network-energy! network-manager/set-network-energy!)
(def transfer-network-energy! network-manager/transfer-network-energy!)