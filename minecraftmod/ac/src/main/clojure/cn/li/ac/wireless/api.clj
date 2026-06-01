(ns cn.li.ac.wireless.api
	"Canonical public API for wireless queries and topology commands."
	(:require [cn.li.ac.wireless.core.vblock :as vb]
					[cn.li.ac.wireless.core.capability-resolver :as resolver]
					[cn.li.ac.wireless.data.network-state :as network-state]
					[cn.li.ac.wireless.service.network-command :as network-command]
					[cn.li.ac.wireless.service.query-service :as query-service]
					[cn.li.ac.wireless.service.world-registry :as world-registry]
					[cn.li.mcmod.platform.be :as platform-be]
					[cn.li.mcmod.platform.events :as platform-events])
	(:import [cn.li.acapi.wireless
						IWirelessGenerator
						IWirelessMatrix
						IWirelessNode
						IWirelessReceiver]))

(def network-snapshot network-state/snapshot)
(def network-ssid network-state/get-ssid)
(def network-password network-state/get-password)
(def network-load network-state/get-load)
(def network-active? network-state/active?)

(defn get-wireless-net-by-matrix
	[matrix-tile]
	(query-service/find-network-by-matrix matrix-tile))

(defn get-wireless-net-by-node
	[node-tile]
	(query-service/find-network-by-node node-tile))

(defn get-wireless-net-by-ssid
	[world ssid]
	(query-service/find-network-by-ssid world ssid))

(defn is-node-linked?
	[node-tile]
	(some? (get-wireless-net-by-node node-tile)))

(defn is-matrix-active?
	[matrix-tile]
	(some? (get-wireless-net-by-matrix matrix-tile)))

(defn get-nets-in-range
	[world x y z range max-results]
	(query-service/find-networks-in-range world x y z range max-results))

(defn get-node-conn-by-node
	[node-tile]
	(query-service/find-node-connection-by-node node-tile))

(defn get-node-conn-by-generator
	[gen-tile]
	(query-service/find-node-connection-by-generator gen-tile))

(defn get-node-conn-by-receiver
	[rec-tile]
	(query-service/find-node-connection-by-receiver rec-tile))

(defn is-receiver-linked?
	[rec-tile]
	(some? (get-node-conn-by-receiver rec-tile)))

(defn is-generator-linked?
	[gen-tile]
	(some? (get-node-conn-by-generator gen-tile)))

(defn get-nodes-in-range-at
	[world x y z]
	(query-service/find-available-nodes-at world x y z))

(defn get-nodes-in-range
	[world pos]
	(query-service/find-available-nodes world pos))

(declare destroy-network!)

(defn create-network!
	[matrix-tile ssid password]
	(let [world (platform-be/be-get-world-safe matrix-tile)
				world-data (world-registry/get-world-data world)
				matrix-vb (vb/create-vmatrix matrix-tile)
        ;; Functional parity: recreating a network on the same matrix replaces the old one.
        _ (when-let [old (world-registry/get-network-by-matrix world-data matrix-vb)]
            (destroy-network! matrix-tile))
				created? (network-command/create-network! world-data matrix-vb ssid password)]
		(when created?
				(when-let [matrix-cap (resolver/matrix-capability matrix-tile)]
				(platform-events/fire-event!
					{:kind :topology/network
					 :action :created
					 :ssid ssid
					 :matrix ^IWirelessMatrix matrix-cap})))
		created?))

(defn destroy-network!
	[matrix-tile]
	(when-let [network-item (get-wireless-net-by-matrix matrix-tile)]
		(let [world (platform-be/be-get-world-safe matrix-tile)
					world-data (world-registry/get-world-data world)
					ssid (network-state/get-ssid network-item)
					destroyed? (network-command/destroy-network! world-data network-item)]
			(when destroyed?
					(when-let [matrix-cap (resolver/matrix-capability matrix-tile)]
					(platform-events/fire-event!
						{:kind :topology/network
						 :action :destroyed
						 :ssid ssid
						 :matrix ^IWirelessMatrix matrix-cap})))
			destroyed?)))

(defn link-node-to-network!
	[node-tile matrix-tile password]
	(when-let [network-item (get-wireless-net-by-matrix matrix-tile)]
		(let [world (platform-be/be-get-world-safe matrix-tile)
					world-data (world-registry/get-world-data world)
					node-vb (vb/create-vnode node-tile)
					linked? (network-command/link-node-to-network! world-data network-item node-vb password)]
			(when linked?
				(when-let [matrix-cap (resolver/matrix-capability matrix-tile)]
					(when-let [node-cap (resolver/node-capability node-tile)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :connected
							 :matrix ^IWirelessMatrix matrix-cap
							 :node ^IWirelessNode node-cap}))))
			linked?)))

(defn unlink-node-from-network!
	[node-tile]
	(when-let [network-item (get-wireless-net-by-node node-tile)]
		(let [world (platform-be/be-get-world-safe node-tile)
					matrix-tile (when-let [matrix-vb (:matrix network-item)]
										(vb/vblock-get matrix-vb world))
					node-vb (vb/create-vnode node-tile)
					removed? (network-command/unlink-node-from-network! network-item node-vb)]
			(when removed?
					(when-let [node-cap (resolver/node-capability node-tile)]
						(when-let [matrix-cap (some-> matrix-tile resolver/matrix-capability)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :disconnected
							 :matrix ^IWirelessMatrix matrix-cap
							 :node ^IWirelessNode node-cap}))))
			removed?)))

(defn connect-node-to-ssid!
	[world node-tile ssid password]
	(let [network (get-wireless-net-by-ssid world ssid)
			matrix-tile (when-let [matrix-vb (:matrix network)]
								(vb/vblock-get matrix-vb world))]
		(boolean
			(when (and network matrix-tile)
				(link-node-to-network! node-tile matrix-tile password)))))

(defn link-generator-to-node!
	[gen-tile node-tile password need-auth]
	(when-let [node-cap (resolver/node-capability node-tile)]
		(when (or (not need-auth)
						(= password (.getPassword ^IWirelessNode node-cap)))
			(let [world (platform-be/be-get-world-safe node-tile)
						world-data (world-registry/get-world-data world)
						node-vb (vb/create-vnode-conn node-tile)
						conn (network-command/ensure-node-connection! world-data node-vb)
						gen-vb (vb/create-vgenerator gen-tile)
						linked? (network-command/link-generator-to-connection! world-data conn gen-vb)]
				(when linked?
						(when-let [gen-cap (resolver/generator-capability gen-tile)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :generator-linked
							 :node ^IWirelessNode node-cap
							 :generator ^IWirelessGenerator gen-cap})))
				linked?))))

(defn unlink-generator-from-node!
	[gen-tile]
	(when-let [conn (get-node-conn-by-generator gen-tile)]
		(let [world (platform-be/be-get-world-safe gen-tile)
          gen-vb (vb/create-vgenerator gen-tile)
          removed? (network-command/unlink-generator-from-connection! conn gen-vb)]
      (when removed?
        (when-let [gen-cap (resolver/generator-capability gen-tile)]
          (when-let [node-cap (resolver/resolve-node-cap world (:node conn))]
            (platform-events/fire-event!
              {:kind :topology/node
               :action :generator-unlinked
               :node ^IWirelessNode node-cap
               :generator ^IWirelessGenerator gen-cap}))))
      removed?)))

(defn link-receiver-to-node!
	[rec-tile node-tile password need-auth]
	(when-let [node-cap (resolver/node-capability node-tile)]
		(when (or (not need-auth)
						(= password (.getPassword ^IWirelessNode node-cap)))
			(let [world (platform-be/be-get-world-safe node-tile)
						world-data (world-registry/get-world-data world)
						node-vb (vb/create-vnode-conn node-tile)
						conn (network-command/ensure-node-connection! world-data node-vb)
						rec-vb (vb/create-vreceiver rec-tile)
						linked? (network-command/link-receiver-to-connection! world-data conn rec-vb)]
				(when linked?
						(when-let [rec-cap (resolver/receiver-capability rec-tile)]
						(platform-events/fire-event!
							{:kind :topology/node
							 :action :receiver-linked
							 :node ^IWirelessNode node-cap
							 :receiver ^IWirelessReceiver rec-cap})))
				linked?))))

(defn unlink-receiver-from-node!
	[rec-tile]
	(when-let [conn (get-node-conn-by-receiver rec-tile)]
		(let [world (platform-be/be-get-world-safe rec-tile)
          rec-vb (vb/create-vreceiver rec-tile)
          removed? (network-command/unlink-receiver-from-connection! conn rec-vb)]
      (when removed?
        (when-let [rec-cap (resolver/receiver-capability rec-tile)]
          (when-let [node-cap (resolver/resolve-node-cap world (:node conn))]
            (platform-events/fire-event!
              {:kind :topology/node
               :action :receiver-unlinked
               :node ^IWirelessNode node-cap
               :receiver ^IWirelessReceiver rec-cap}))))
      removed?)))

(defn change-network-ssid!
	[network new-ssid]
	(network-command/change-network-ssid! network new-ssid))

(defn change-network-password!
	[network new-password]
	(network-command/reset-network-password! network new-password))