(ns cn.li.ac.wireless.service.commands
  "Application-level wireless topology commands.

  Orchestrates pure `wireless.domain.topology` transitions, commits through
  `wireless.data.world-registry/transact!` and `entity-commit`, and owns logging."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.entity-commit :as entity-commit]
            [cn.li.ac.wireless.data.network-lookup :as lookup]
            [cn.li.ac.wireless.data.network-mutation :as network-mutation]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.spatial-lookup :as spatial]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.domain.topology :as topology]
            [cn.li.mcmod.util.log :as log]))

(defn add-spatial-vblock!
  [world-data vblock]
  (spatial/add-to-spatial-index! world-data vblock))

(defn remove-spatial-vblock!
  [world-data vblock]
  (spatial/remove-from-spatial-index! world-data vblock))

(defn create-network!
  [world-data matrix-vblock ssid password]
  (if-not (topology/can-create-network? world-data ssid matrix-vblock)
    false
    (let [net (network-state/create-wireless-net world-data matrix-vblock ssid password)]
      (world-registry/transact!
        world-data
        (fn [_]
          (world-registry/update-state! world-data topology/register-network net)
          (log/info (format "Created network: SSID='%s'" ssid))
          true)))))

(defn destroy-network!
  [world-data item]
  (let [item (entity-commit/resolve-network world-data item)]
    (world-registry/transact!
      world-data
      (fn [_]
        (let [disposed (network-state/mark-disposed! item)]
          (world-registry/update-state! world-data topology/unregister-network disposed)
          (log/info (format "Destroyed network: SSID='%s'" (network-state/get-ssid disposed)))
          true)))))

(defn create-node-connection!
  [world-data node-vblock]
  (if (lookup/get-node-connection world-data node-vblock)
    false
    (let [conn (node-conn/create-node-conn world-data node-vblock)]
      (world-registry/transact!
        world-data
        (fn [_]
          (world-registry/update-state! world-data topology/register-connection conn)
          conn)))))

(defn destroy-node-connection!
  [world-data item]
  (world-registry/transact!
    world-data
    (fn [_]
      (let [disposed (node-conn/set-disposed! item true)]
        (world-registry/update-state! world-data topology/unregister-connection disposed)
        (log/info (format "Destroyed node connection: %s"
                          (vb/vblock-to-string (:node item))))
        true))))

(defn ensure-node-connection!
  [world-data node-vblock]
  (or (lookup/get-node-connection world-data node-vblock)
      (create-node-connection! world-data node-vblock)))

(defn- remove-node-in-transaction!
  [world-data network node-vblock]
  (let [resolved (entity-commit/resolve-network world-data network)
        {:keys [network removed?]} (topology/remove-node-from-network resolved node-vblock)]
    (when removed?
      (entity-commit/replace-network-in-state! world-data resolved network)
      (world-registry/update-state! world-data topology/unlink-network-node node-vblock)
      (log/info (format "Removed node %s from '%s'"
                        (vb/vblock-to-string node-vblock)
                        (network-state/get-ssid network))))
    removed?))

(defn unlink-node-from-network!
  [network-item node-vb]
  (world-registry/transact!
    (:world-data network-item)
    (fn [_]
      (remove-node-in-transaction! (:world-data network-item) network-item node-vb))))

(defn link-node-to-network!
  [world-data net node-vblock password-attempt]
  (world-registry/transact!
    world-data
    (fn [_]
      (let [net (entity-commit/resolve-network world-data net)]
        (when-let [old-net (lookup/get-network-by-node world-data node-vblock)]
          (when-not (identical? old-net net)
            (remove-node-in-transaction! world-data old-net node-vblock)))
        (let [validation (topology/validate-add-node net node-vblock password-attempt)]
          (if-not (:ok validation)
            (do
              (case (:reason validation)
                :password
                (log/info (format "Node add failed: incorrect password for '%s'"
                                  (network-state/get-ssid net)))
                :capacity
                (log/info (format "Node add failed: network '%s' at capacity"
                                  (network-state/get-ssid net)))
                :range
                (log/info (format "Node add failed: node %s out of matrix range"
                                  (vb/vblock-to-string node-vblock))))
              false)
            (let [net* (topology/add-node-to-network net node-vblock)]
              (entity-commit/replace-network-in-state! world-data net net*)
              (world-registry/update-state!
                world-data
                #(topology/link-network-node % net* node-vblock))
              (log/info (format "Added node %s to network '%s'"
                                (vb/vblock-to-string node-vblock)
                                (network-state/get-ssid net*)))
              true)))))))

(defn link-generator-to-connection!
  [world-data conn generator-vblock]
  (when-let [old-conn (lookup/get-node-connection world-data generator-vblock)]
    (node-conn/remove-generator! old-conn generator-vblock))
  (node-conn/add-generator! conn generator-vblock))

(defn unlink-generator-from-connection!
  [conn gen-vb]
  (node-conn/remove-generator! conn gen-vb))

(defn link-receiver-to-connection!
  [world-data conn receiver-vblock]
  (when-let [old-conn (lookup/get-node-connection world-data receiver-vblock)]
    (node-conn/remove-receiver! old-conn receiver-vblock))
  (node-conn/add-receiver! conn receiver-vblock))

(defn unlink-receiver-from-connection!
  [conn rec-vb]
  (node-conn/remove-receiver! conn rec-vb))

(defn change-network-ssid!
  [network-item new-ssid]
  (let [world-data (:world-data network-item)
        network-item (entity-commit/resolve-network world-data network-item)
        old-ssid (network-state/get-ssid network-item)]
    (cond
      (= old-ssid new-ssid)
      true

      (not (topology/ssid-available? world-data network-item new-ssid))
      (do
        (log/info (format "SSID change rejected: target SSID '%s' already exists" new-ssid))
        false)

      :else
      (world-registry/transact!
        world-data
        (fn [_]
          (let [network* (network-mutation/reset-ssid! network-item new-ssid)]
            (world-registry/update-state!
              world-data
              #(topology/refresh-ssid-lookup % old-ssid new-ssid network*))
            true))))))

(defn reset-network-password!
  [network-item new-password]
  (network-mutation/reset-password! network-item new-password))

(defn rebuild-network-indexes!
  [world-data net]
  (world-registry/transact!
    world-data
    (fn [_]
      (world-registry/update-state! world-data topology/rebuild-network-indexes net)
      net)))

(defn rebuild-connection-indexes!
  [world-data conn]
  (world-registry/transact!
    world-data
    (fn [_]
      (world-registry/update-state! world-data topology/rebuild-connection-indexes conn)
      conn)))
