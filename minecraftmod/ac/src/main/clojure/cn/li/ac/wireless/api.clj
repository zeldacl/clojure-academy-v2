(ns cn.li.ac.wireless.api
	"Wireless system compatibility facade.

	Split into:
	- cn.li.ac.wireless.api-query
	- cn.li.ac.wireless.api-command
	- cn.li.ac.wireless.api-lifecycle"
	(:require [cn.li.ac.wireless.api-query :as api-query]
						[cn.li.ac.wireless.api-command :as api-command]
						[cn.li.ac.wireless.api-lifecycle :as api-lifecycle]
						[cn.li.mcmod.util.log :as log]))

;; Queries
(def get-wireless-net-by-matrix api-query/get-wireless-net-by-matrix)
(def get-wireless-net-by-node api-query/get-wireless-net-by-node)
(def is-node-linked? api-query/is-node-linked?)
(def is-matrix-active? api-query/is-matrix-active?)
(def get-nets-in-range api-query/get-nets-in-range)
(def get-node-conn-by-node api-query/get-node-conn-by-node)
(def get-node-conn-by-generator api-query/get-node-conn-by-generator)
(def get-node-conn-by-receiver api-query/get-node-conn-by-receiver)
(def is-receiver-linked? api-query/is-receiver-linked?)
(def is-generator-linked? api-query/is-generator-linked?)
(def get-nodes-in-range-at api-query/get-nodes-in-range-at)
(def get-nodes-in-range api-query/get-nodes-in-range)

;; Commands
(def create-network! api-command/create-network!)
(def destroy-network! api-command/destroy-network!)
(def link-node-to-network! api-command/link-node-to-network!)
(def unlink-node-from-network! api-command/unlink-node-from-network!)
(def link-generator-to-node! api-command/link-generator-to-node!)
(def unlink-generator-from-node! api-command/unlink-generator-from-node!)
(def link-receiver-to-node! api-command/link-receiver-to-node!)
(def unlink-receiver-from-node! api-command/unlink-receiver-from-node!)

;; Lifecycle / diagnostics
(def tick-wireless-system! api-lifecycle/tick-wireless-system!)
(def save-wireless-data api-lifecycle/save-wireless-data)
(def load-wireless-data api-lifecycle/load-wireless-data)
(def print-wireless-stats api-lifecycle/print-wireless-stats)
(def get-all-networks api-lifecycle/get-all-networks)
(def get-all-connections api-lifecycle/get-all-connections)

(defn init-wireless-helper! []
	(log/info "Wireless helper system initialized"))