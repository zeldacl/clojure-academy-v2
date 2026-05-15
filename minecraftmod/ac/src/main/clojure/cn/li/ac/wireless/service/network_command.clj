(ns cn.li.ac.wireless.service.network-command
  "Service-level wireless topology mutation helpers.

  Keeps imperative network/connection operations in one place so API facade
  namespaces stay thin and migration-friendly."
  (:require [cn.li.ac.wireless.data.network-membership :as network-membership]
            [cn.li.ac.wireless.data.network-mutation :as network-mutation]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.service.node-connection :as node-connection]
            [cn.li.ac.wireless.service.world-registry :as world-registry]))

(defn create-network!
  [world-data matrix-vb ssid password]
  (world-registry/create-network-impl! world-data matrix-vb ssid password))

(defn destroy-network!
  [world-data network-item]
  (world-registry/destroy-network-impl! world-data network-item))

(defn link-node-to-network!
  [world-data network-item node-vb password]
  (world-registry/link-node-to-network! world-data network-item node-vb password))

(defn unlink-node-from-network!
  [network-item node-vb]
  (network-membership/remove-node! network-item node-vb))

(defn network-load
  [network-item]
  (network-state/get-load network-item))

(defn reset-network-ssid!
  [network-item new-ssid]
  (network-mutation/reset-ssid! network-item new-ssid))

(defn reset-network-password!
  [network-item new-password]
  (network-mutation/reset-password! network-item new-password))

(defn refresh-world-ssid-lookup!
  [network-item old-ssid new-ssid]
  (let [world-data (:world-data network-item)]
    (swap! (:net-lookup world-data) dissoc old-ssid)
    (swap! (:net-lookup world-data) assoc new-ssid network-item)))

(defn ensure-node-connection!
  [world-data node-vb]
  (world-registry/ensure-node-connection! world-data node-vb))

(defn link-generator-to-connection!
  [world-data conn gen-vb]
  (world-registry/link-generator-to-node-connection! world-data conn gen-vb))

(defn unlink-generator-from-connection!
  [conn gen-vb]
  (node-connection/remove-generator! conn gen-vb))

(defn link-receiver-to-connection!
  [world-data conn rec-vb]
  (world-registry/link-receiver-to-node-connection! world-data conn rec-vb))

(defn unlink-receiver-from-connection!
  [conn rec-vb]
  (node-connection/remove-receiver! conn rec-vb))