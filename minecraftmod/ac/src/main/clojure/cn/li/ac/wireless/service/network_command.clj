(ns cn.li.ac.wireless.service.network-command
  "Service-level wireless topology mutation helpers.

  Keeps imperative network/connection operations in one place so API facade
  namespaces stay thin and migration-friendly."
  (:require [cn.li.ac.wireless.service.topology-service :as topology-service]))

(defn create-network!
  [world-data matrix-vb ssid password]
  (topology-service/create-network! world-data matrix-vb ssid password))

(defn destroy-network!
  [world-data network-item]
  (topology-service/destroy-network! world-data network-item))

(defn link-node-to-network!
  [world-data network-item node-vb password]
  (topology-service/link-node-to-network! world-data network-item node-vb password))

(defn unlink-node-from-network!
  [network-item node-vb]
  (topology-service/unlink-node-from-network! network-item node-vb))

(defn network-load
  [network-item]
  (topology-service/network-load network-item))

(defn reset-network-ssid!
  [network-item new-ssid]
  (topology-service/reset-network-ssid! network-item new-ssid))

(defn reset-network-password!
  [network-item new-password]
  (topology-service/reset-network-password! network-item new-password))

(defn refresh-world-ssid-lookup!
  [network-item old-ssid new-ssid]
  (topology-service/refresh-world-ssid-lookup! network-item old-ssid new-ssid))

(defn change-network-ssid!
  [network-item new-ssid]
  (topology-service/change-network-ssid! network-item new-ssid))

(defn ensure-node-connection!
  [world-data node-vb]
  (topology-service/ensure-node-connection! world-data node-vb))

(defn link-generator-to-connection!
  [world-data conn gen-vb]
  (topology-service/link-generator-to-connection! world-data conn gen-vb))

(defn unlink-generator-from-connection!
  [conn gen-vb]
  (topology-service/unlink-generator-from-connection! conn gen-vb))

(defn link-receiver-to-connection!
  [world-data conn rec-vb]
  (topology-service/link-receiver-to-connection! world-data conn rec-vb))

(defn unlink-receiver-from-connection!
  [conn rec-vb]
  (topology-service/unlink-receiver-from-connection! conn rec-vb))