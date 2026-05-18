(ns cn.li.ac.wireless.service.topology-service
  "Application-level wireless topology mutation service.

  This namespace owns business command orchestration: uniqueness checks,
  password/capacity/range checks delegated to domain/data helpers, relinking, and
  metadata changes. Low-level index updates remain in `wireless.data.topology-index`."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-membership :as network-membership]
            [cn.li.ac.wireless.data.network-mutation :as network-mutation]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.topology-index :as topology-index]
            [cn.li.mcmod.util.log :as log]))

(defn create-network!
  "Create network and register lookups/indexes.
  Returns true on success, false when uniqueness checks fail."
  [world-data matrix-vblock ssid password]
  (cond
    (topology-index/get-network-by-ssid world-data ssid)
    false

    (topology-index/get-network-by-matrix world-data matrix-vblock)
    false

    :else
    (let [item (network-state/create-wireless-net world-data matrix-vblock ssid password)]
      (topology-index/register-network! world-data item)
      (log/info (format "Created network: SSID='%s'" ssid))
      true)))

(defn destroy-network!
  "Destroy network and clear all related lookups/indexes."
  [world-data item]
  (reset! (:disposed item) true)
  (topology-index/unregister-network! world-data item)
  (log/info (format "Destroyed network: SSID='%s'" (network-state/get-ssid item)))
  true)

(defn create-node-connection!
  "Create node connection and register lookups/indexes.
  Returns created connection, or false if it already exists."
  [world-data node-vblock]
  (if (topology-index/get-node-connection world-data node-vblock)
    false
    (let [item (node-conn/create-node-conn world-data node-vblock)]
      (topology-index/register-node-connection! world-data item))))

(defn destroy-node-connection!
  "Destroy node connection and clear all related lookups/indexes."
  [world-data item]
  (reset! (:disposed item) true)
  (topology-index/unregister-node-connection! world-data item)
  (log/info (format "Destroyed node connection: %s" (vb/vblock-to-string (:node item))))
  true)

(defn ensure-node-connection!
  "Get or create node connection for a node."
  [world-data node-vblock]
  (or (topology-index/get-node-connection world-data node-vblock)
      (create-node-connection! world-data node-vblock)))

(defn link-node-to-network!
  "Link a node vblock into the specified network with password check."
  [world-data net node-vblock password-attempt]
  (when-let [old-net (topology-index/get-network-by-node world-data node-vblock)]
    (network-membership/remove-node! old-net node-vblock))
  (network-membership/add-node! net node-vblock password-attempt))

(defn unlink-node-from-network!
  [network-item node-vb]
  (network-membership/remove-node! network-item node-vb))

(defn link-generator-to-connection!
  "Link a generator vblock to a node connection."
  [world-data conn generator-vblock]
  (when-let [old-conn (topology-index/get-node-connection world-data generator-vblock)]
    (node-conn/remove-generator! old-conn generator-vblock))
  (node-conn/add-generator! conn generator-vblock))

(defn unlink-generator-from-connection!
  [conn gen-vb]
  (node-conn/remove-generator! conn gen-vb))

(defn link-receiver-to-connection!
  "Link a receiver vblock to a node connection."
  [world-data conn receiver-vblock]
  (when-let [old-conn (topology-index/get-node-connection world-data receiver-vblock)]
    (node-conn/remove-receiver! old-conn receiver-vblock))
  (node-conn/add-receiver! conn receiver-vblock))

(defn unlink-receiver-from-connection!
  [conn rec-vb]
  (node-conn/remove-receiver! conn rec-vb))

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

(defn change-network-ssid!
  [network-item new-ssid]
  (let [old-ssid (network-state/get-ssid network-item)]
    (reset-network-ssid! network-item new-ssid)
    (refresh-world-ssid-lookup! network-item old-ssid new-ssid)
    true))
