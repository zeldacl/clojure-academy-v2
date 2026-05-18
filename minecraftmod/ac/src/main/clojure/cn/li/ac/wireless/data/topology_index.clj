(ns cn.li.ac.wireless.data.topology-index
  "Low-level topology index mutations for WiWorldData.

  This namespace owns registry/index bookkeeping only. Business orchestration
  such as uniqueness checks, password/capacity checks, and device relinking lives
  in `wireless.service.topology-service`."
  (:require [cn.li.ac.wireless.data.network-lookup :as network-lookup]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.spatial-lookup :as spatial-lookup]
            [cn.li.ac.wireless.data.world-registry :as world-registry]))

(defn add-to-spatial-index!
  [world-data vblock]
  (spatial-lookup/add-to-spatial-index! world-data vblock))

(defn remove-from-spatial-index!
  [world-data vblock]
  (spatial-lookup/remove-from-spatial-index! world-data vblock))

(defn get-network-by-matrix
  [world-data matrix-vblock]
  (network-lookup/get-network-by-matrix world-data matrix-vblock))

(defn get-network-by-node
  [world-data node-vblock]
  (network-lookup/get-network-by-node world-data node-vblock))

(defn get-network-by-ssid
  [world-data ssid]
  (network-lookup/get-network-by-ssid world-data ssid))

(defn get-node-connection
  [world-data vblock]
  (network-lookup/get-node-connection world-data vblock))

(defn register-network!
  [world-data net]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:networks world-data) conj net)
      (swap! (:net-lookup world-data) assoc (:matrix net) net (network-state/get-ssid net) net)
      (add-to-spatial-index! world-data (:matrix net))
      net)))

(defn register-network-node!
  [world-data net node-vblock]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:net-lookup world-data) assoc node-vblock net)
      (add-to-spatial-index! world-data node-vblock))))

(defn unregister-network-node!
  [world-data node-vblock]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:net-lookup world-data) dissoc node-vblock)
      (remove-from-spatial-index! world-data node-vblock))))

(defn unregister-network!
  [world-data net]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:net-lookup world-data) dissoc (:matrix net) (network-state/get-ssid net))
      (doseq [node (network-state/get-nodes net)]
        (unregister-network-node! world-data node))
      (remove-from-spatial-index! world-data (:matrix net))
      (swap! (:networks world-data) (fn [items] (filterv #(not= % net) items))))))

(defn register-node-connection!
  [world-data conn]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:connections world-data) conj conn)
      (swap! (:node-lookup world-data) assoc (:node conn) conn)
      (add-to-spatial-index! world-data (:node conn))
      conn)))

(defn register-node-device!
  [world-data conn device-vblock]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:node-lookup world-data) assoc device-vblock conn))))

(defn unregister-node-device!
  [world-data device-vblock]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:node-lookup world-data) dissoc device-vblock))))

(defn unregister-node-connection!
  [world-data conn]
  (world-registry/transact!
    world-data
    (fn [_]
      (swap! (:node-lookup world-data) dissoc (:node conn))
      (doseq [device (node-conn/get-generators conn)]
        (unregister-node-device! world-data device))
      (doseq [device (node-conn/get-receivers conn)]
        (unregister-node-device! world-data device))
      (remove-from-spatial-index! world-data (:node conn))
      (swap! (:connections world-data) (fn [items] (filterv #(not= % conn) items))))))

(defn rebuild-network-indexes!
  [world-data net]
  (register-network! world-data net)
  (doseq [node (network-state/get-nodes net)]
    (register-network-node! world-data net node)))

(defn rebuild-connection-indexes!
  [world-data conn]
  (register-node-connection! world-data conn)
  (doseq [generator (node-conn/get-generators conn)]
    (register-node-device! world-data conn generator))
  (doseq [receiver (node-conn/get-receivers conn)]
    (register-node-device! world-data conn receiver)))
