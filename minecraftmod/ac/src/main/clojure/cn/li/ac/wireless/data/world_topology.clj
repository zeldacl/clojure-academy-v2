(ns cn.li.ac.wireless.data.world-topology
	(:require [cn.li.ac.wireless.core.vblock :as vb]
						[cn.li.ac.wireless.data.spatial-lookup :as spatial-lookup]
						[cn.li.ac.wireless.data.network-lookup :as network-lookup]
						[cn.li.ac.wireless.data.network-state :as network-state]
						[cn.li.ac.wireless.data.network-membership :as network-membership]
						[cn.li.ac.wireless.data.node-conn :as node-conn]
						[cn.li.ac.wireless.data.world-registry :as world-registry]
						[cn.li.mcmod.util.log :as log]))

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

(defn create-network-impl!
	"Create network and register lookups/indexes.
	Returns true on success, false when uniqueness checks fail."
	[world-data matrix-vblock ssid password]
	(cond
		(get-network-by-ssid world-data ssid)
		false

		(get-network-by-matrix world-data matrix-vblock)
		false

		:else
		(let [item (network-state/create-wireless-net world-data matrix-vblock ssid password)]
			(register-network! world-data item)
			(log/info (format "Created network: SSID='%s'" ssid))
			true)))

(defn destroy-network-impl!
	"Destroy network and clear all related lookups/indexes."
	[world-data item]
	(reset! (:disposed item) true)
	(unregister-network! world-data item)
	(log/info (format "Destroyed network: SSID='%s'" (network-state/get-ssid item))))

(defn create-node-connection-impl!
	"Create node connection and register lookups/indexes.
	Returns created connection, or false if it already exists."
	[world-data node-vblock]
	(if (get-node-connection world-data node-vblock)
		false
		(let [item (node-conn/create-node-conn world-data node-vblock)]
			(register-node-connection! world-data item))))

(defn destroy-node-connection-impl!
	"Destroy node connection and clear all related lookups/indexes."
	[world-data item]
	(reset! (:disposed item) true)
	(unregister-node-connection! world-data item)
	(log/info (format "Destroyed node connection: %s" (vb/vblock-to-string (:node item)))))

(defn ensure-node-connection!
	"Get or create node connection for a node."
	[world-data node-vblock]
	(or (get-node-connection world-data node-vblock)
			(create-node-connection-impl! world-data node-vblock)))

(defn link-node-to-network!
	"Link a node vblock into the specified network with password check."
	[world-data net node-vblock password-attempt]
	(when-let [old-net (get-network-by-node world-data node-vblock)]
		(network-membership/remove-node! old-net node-vblock))
	(network-membership/add-node! net node-vblock password-attempt))

(defn link-generator-to-node-connection!
	"Link a generator vblock to a node connection."
	[world-data conn generator-vblock]
	(when-let [old-conn (get-node-connection world-data generator-vblock)]
		(node-conn/remove-generator! old-conn generator-vblock))
	(node-conn/add-generator! conn generator-vblock))

(defn link-receiver-to-node-connection!
	"Link a receiver vblock to a node connection."
	[world-data conn receiver-vblock]
	(when-let [old-conn (get-node-connection world-data receiver-vblock)]
		(node-conn/remove-receiver! old-conn receiver-vblock))
	(node-conn/add-receiver! conn receiver-vblock))

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