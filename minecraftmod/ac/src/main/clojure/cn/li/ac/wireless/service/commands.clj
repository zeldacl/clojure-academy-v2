(ns cn.li.ac.wireless.service.commands
  "Application-level wireless topology commands.

  Orchestrates pure `wireless.domain.topology` transitions, commits through
  `entity-commit` and `world-registry/update-state!` (each already atomic via swap!), and owns logging."
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
    {:success false :reason :ssid-exists}
    (let [net (network-state/create-wireless-net world-data matrix-vblock ssid password)]
      ;; Direct atomic update via update-state! (already uses swap! internally).
      ;; WRAPPING with transact! here causes a double-swap! overwrite:
      ;;   transact! reads base → mutation-fn swap! writes new state
      ;;   → transact! swap! writes base back, undoing the mutation.
      (world-registry/update-state! world-data topology/register-network net)
      (log/info (format "Created network: SSID='%s'" ssid))
      {:success true})))

(defn destroy-network!
  [world-data item]
  (let [item (entity-commit/resolve-network world-data item)
        disposed (network-state/mark-disposed! item)]
    ;; update-state! is already atomic via its own swap! — no transact! needed.
    (world-registry/update-state! world-data topology/unregister-network disposed)
    (log/info (format "Destroyed network: SSID='%s'" (network-state/get-ssid disposed)))
    {:success true}))

(defn create-node-connection!
  [world-data node-vblock]
  (if (lookup/get-node-connection world-data node-vblock)
    {:success false :reason :already-exists}
    (let [conn (node-conn/create-node-conn world-data node-vblock)]
      ;; update-state! is already atomic via its own swap!.
      (world-registry/update-state! world-data topology/register-connection conn)
      {:success true :connection conn})))

(defn destroy-node-connection!
  [world-data item]
  (let [disposed (node-conn/set-disposed! item true)]
    ;; update-state! is already atomic via its own swap!.
    (world-registry/update-state! world-data topology/unregister-connection disposed)
    (log/info (format "Destroyed node connection: %s"
                      (vb/vblock-to-string (:node item))))
    {:success true}))

(defn ensure-node-connection!
  [world-data node-vblock]
  (or (lookup/get-node-connection world-data node-vblock)
      (let [result (create-node-connection! world-data node-vblock)]
        (when (:success result)
          (:connection result)))))

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
  ;; remove-node-in-transaction! uses update-state! which is already atomic.
  (if (remove-node-in-transaction! (:world-data network-item) network-item node-vb)
    {:success true}
    {:success false}))

(defn link-node-to-network!
  [world-data net node-vblock password-attempt]
  ;; Each sub-operation (replace-network-in-state!, update-state!) is already
  ;; atomic via its own swap!. The transact! wrapper was harmful because its
  ;; outer swap! would overwrite the inner updates with the original snapshot.
  (let [net (entity-commit/resolve-network world-data net)]
    (when-let [old-net (lookup/get-network-by-node world-data node-vblock)]
      (when-not (identical? old-net net)
        (remove-node-in-transaction! world-data old-net node-vblock)))
    (let [validation (topology/validate-add-node net node-vblock password-attempt)]
      (if-not (:ok validation)
        (do
          (log/info (format "Node add failed: reason=%s for '%s'"
                            (:reason validation)
                            (network-state/get-ssid net)))
          {:success false :reason (:reason validation)})
        (let [net* (topology/add-node-to-network net node-vblock)]
          (entity-commit/replace-network-in-state! world-data net net*)
          (world-registry/update-state!
            world-data
            #(topology/link-network-node % net* node-vblock))
          (log/info (format "Added node %s to network '%s'"
                            (vb/vblock-to-string node-vblock)
                            (network-state/get-ssid net*)))
          {:success true})))))

(defn link-generator-to-connection!
  [world-data conn generator-vblock]
  (when-let [old-conn (lookup/get-node-connection world-data generator-vblock)]
    (node-conn/remove-generator! old-conn generator-vblock))
  {:success (boolean (node-conn/add-generator! conn generator-vblock))})

(defn unlink-generator-from-connection!
  [conn gen-vb]
  {:success (boolean (node-conn/remove-generator! conn gen-vb))})

(defn link-receiver-to-connection!
  [world-data conn receiver-vblock]
  (when-let [old-conn (lookup/get-node-connection world-data receiver-vblock)]
    (node-conn/remove-receiver! old-conn receiver-vblock))
  {:success (boolean (node-conn/add-receiver! conn receiver-vblock))})

(defn unlink-receiver-from-connection!
  [conn rec-vb]
  {:success (boolean (node-conn/remove-receiver! conn rec-vb))})

(defn change-network-ssid!
  [network-item new-ssid]
  (let [world-data (:world-data network-item)
        network-item (entity-commit/resolve-network world-data network-item)
        old-ssid (network-state/get-ssid network-item)]
    (cond
      (= old-ssid new-ssid)
      {:success true}

      (not (topology/ssid-available? world-data network-item new-ssid))
      (do
        (log/info (format "SSID change rejected: target SSID '%s' already exists" new-ssid))
        {:success false :reason :ssid-taken})

      :else
      ;; update-state! is already atomic via its own swap!.
      (let [network* (network-mutation/reset-ssid! network-item new-ssid)]
        (world-registry/update-state!
          world-data
          #(topology/refresh-ssid-lookup % old-ssid new-ssid network*))
        {:success true}))))

(defn reset-network-password!
  [network-item new-password]
  (network-mutation/reset-password! network-item new-password)
  {:success true})

(defn rebuild-network-indexes!
  [world-data net]
  ;; update-state! is already atomic via its own swap!.
  (world-registry/update-state! world-data topology/rebuild-network-indexes net)
  {:success true})

(defn rebuild-connection-indexes!
  [world-data conn]
  ;; update-state! is already atomic via its own swap!.
  (world-registry/update-state! world-data topology/rebuild-connection-indexes conn)
  {:success true})
