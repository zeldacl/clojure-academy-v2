(ns cn.li.ac.wireless.data.entity-commit
  "Commit updated wireless network/connection entities back into WiWorldData.

  Replaces entity references in :networks/:connections vectors and lookup maps
  so callers can treat network/connection records as immutable values."
  (:require [cn.li.ac.wireless.data.world-registry :as world-registry]))

(defn- replace-lookup-refs
  [lookup old-ref new-ref]
  (reduce-kv
    (fn [m k v]
      (assoc m k (if (identical? v old-ref) new-ref v)))
    lookup
    lookup))

(defn replace-network-in-state!
  [world-data old-net new-net]
  (world-registry/update-state-value!
    world-data
    :networks
    (fn [items]
      (mapv #(if (identical? % old-net) new-net %) items)))
  (world-registry/update-state-value!
    world-data
    :net-lookup
    (fn [lookup]
      (replace-lookup-refs lookup old-net new-net))))

(defn replace-connection-in-state!
  [world-data old-conn new-conn]
  (world-registry/update-state-value!
    world-data
    :connections
    (fn [items]
      (mapv #(if (identical? % old-conn) new-conn %) items)))
  (world-registry/update-state-value!
    world-data
    :node-lookup
    (fn [lookup]
      (replace-lookup-refs lookup old-conn new-conn))))

(defn network-in-world?
  [world-data network]
  (boolean
    (some #(identical? % network) (world-registry/networks world-data))))

(defn connection-in-world?
  [world-data connection]
  (boolean
    (some #(identical? % connection) (world-registry/connections world-data))))

(defn resolve-network
  "Return the current registered network for `network`, or `network` when unregistered."
  [world-data network]
  (or (get (world-registry/net-lookup world-data) (:matrix network))
      (get (world-registry/net-lookup world-data) (get-in network [:state :ssid]))
      network))

(defn resolve-connection
  "Return the current registered connection for `connection`, or `connection` when unregistered."
  [world-data connection]
  (or (get (world-registry/node-lookup world-data) (:node connection))
      connection))

(defn- commit-lookup-refs!
  [world-data lookup-key old-ref new-ref]
  (when-not (identical? old-ref new-ref)
    (world-registry/update-state-value!
      world-data
      lookup-key
      (fn [lookup]
        (replace-lookup-refs lookup old-ref new-ref)))))

(defn commit-network!
  "Return `new-net`, persisting it into world state when `old-net` is registered."
  [world-data old-net new-net]
  (cond
    (identical? old-net new-net)
    new-net

    (network-in-world? world-data old-net)
    ;; replace-network-in-state! uses update-state-value! which is already atomic via swap!.
    (replace-network-in-state! world-data old-net new-net)

    :else
    (do
      (commit-lookup-refs! world-data :net-lookup old-net new-net)
      new-net)))

(defn commit-connection!
  "Return `new-conn`, persisting it into world state when `old-conn` is registered."
  [world-data old-conn new-conn]
  (cond
    (identical? old-conn new-conn)
    new-conn

    (connection-in-world? world-data old-conn)
    ;; replace-connection-in-state! uses update-state-value! which is already atomic via swap!.
    (replace-connection-in-state! world-data old-conn new-conn)

    :else
    (do
      (commit-lookup-refs! world-data :node-lookup old-conn new-conn)
      new-conn)))

(defn with-committed-network
  [world-data network f]
  (let [updated (f network)]
    (commit-network! world-data network updated)))

(defn with-committed-connection
  [world-data connection f]
  (let [updated (f connection)]
    (commit-connection! world-data connection updated)))
