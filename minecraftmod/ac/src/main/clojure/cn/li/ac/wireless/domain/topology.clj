(ns cn.li.ac.wireless.domain.topology
  "Pure wireless topology transitions over world-state maps.

  No world-registry transact, capability IO, or logging — callers commit via
  `wireless.service.commands` and `wireless.data.entity-commit`."
  (:require [cn.li.ac.wireless.core.spatial-index :as si]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-lookup :as lookup]
            [cn.li.ac.wireless.data.network-state :as net-state]
            [cn.li.ac.wireless.domain.model :as model])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

(defn- connection-generators [conn]
  (vec (or (get-in conn [:state :generators]) [])))

(defn- connection-receivers [conn]
  (vec (or (get-in conn [:state :receivers]) [])))

(defn register-network
  [state net]
  (-> state
      (update :networks conj net)
      (update :net-lookup
              #(-> %
                   (assoc (:matrix net) net)
                   (assoc (net-state/get-ssid net) net)))
      (update :spatial-index si/add-to-index (:matrix net))))

(defn link-network-node
  [state net node-vblock]
  (-> state
      (update :net-lookup assoc node-vblock net)
      (update :spatial-index si/add-to-index node-vblock)))

(defn unlink-network-node
  [state node-vblock]
  (update state :net-lookup dissoc node-vblock))

(defn unregister-network
  [state net]
  (let [state (-> state
                  (update :net-lookup dissoc (:matrix net) (net-state/get-ssid net))
                  (update :networks (fn [items] (filterv #(not= % net) items)))
                  (update :spatial-index si/remove-from-index (:matrix net)))]
    (reduce unlink-network-node state (net-state/get-nodes net))))

(defn register-connection
  [state conn]
  (-> state
      (update :connections conj conn)
      (update :node-lookup assoc (:node conn) conn)
      (update :spatial-index si/add-to-index (:node conn))))

(defn link-connection-device
  [state conn device-vblock]
  (update state :node-lookup assoc device-vblock conn))

(defn unlink-connection-device
  [state device-vblock]
  (update state :node-lookup dissoc device-vblock))

(defn unregister-connection
  [state conn]
  (let [state (-> state
                  (update :node-lookup dissoc (:node conn))
                  (update :connections (fn [items] (filterv #(not= % conn) items)))
                  (update :spatial-index si/remove-from-index (:node conn)))
        devices (concat (connection-generators conn) (connection-receivers conn))]
    (reduce unlink-connection-device state devices)))

(defn rebuild-network-indexes
  [state net]
  (reduce (fn [state node-vb]
            (link-network-node state net node-vb))
          (register-network state net)
          (net-state/get-nodes net)))

(defn rebuild-connection-indexes
  [state conn]
  (let [state (register-connection state conn)]
    (reduce (fn [s device] (link-connection-device s conn device))
            state
            (concat (connection-generators conn) (connection-receivers conn)))))

(defn ssid-available?
  [world-data network new-ssid]
  (let [existing (lookup/get-network-by-ssid world-data new-ssid)]
    (or (nil? existing) (identical? existing network))))

(defn can-create-network?
  [world-data ssid matrix-vblock]
  (and (nil? (lookup/get-network-by-ssid world-data ssid))
       (nil? (lookup/get-network-by-matrix world-data matrix-vblock))))

(defn password-valid?
  [network password-attempt]
  (= password-attempt (net-state/get-password network)))

(defn network-has-node-capacity?
  [network]
  (model/network-has-capacity?
    {:nodes (net-state/get-nodes network)}
    (net-state/get-capacity network)))

(defn matrix-in-range?
  [network node-vblock]
  (if-let [matrix (net-state/get-matrix network)]
    (let [range (.getMatrixRange ^IWirelessMatrix matrix)
          dist-sq (vb/dist-sq node-vblock (:matrix network))]
      (<= dist-sq (* range range)))
    false))

(defn validate-add-node
  [network node-vblock password-attempt]
  (cond
    (not (password-valid? network password-attempt))
    {:ok false :reason :password}

    (not (network-has-node-capacity? network))
    {:ok false :reason :capacity}

    (not (matrix-in-range? network node-vblock))
    {:ok false :reason :range}

    :else
    {:ok true}))

(defn add-node-to-network
  [network node-vblock]
  (net-state/set-state-value
    network
    :nodes
    (conj (net-state/get-nodes network) node-vblock)))

(defn remove-node-from-network
  [network node-vblock]
  (let [nodes (net-state/get-nodes network)
        removed? (boolean (some #(vb/vblock-equals? % node-vblock) nodes))]
    {:network (net-state/set-state-value
                network
                :nodes
                (filterv #(not (vb/vblock-equals? % node-vblock)) nodes))
     :removed? removed?}))

(defn refresh-ssid-lookup
  [state old-ssid new-ssid network]
  (-> state
      (update :net-lookup dissoc old-ssid)
      (update :net-lookup assoc new-ssid network)))
