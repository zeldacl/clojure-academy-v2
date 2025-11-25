(ns my-mod.energy.stub
  "Stub implementations for energy system - to be replaced with real implementations"
  (:require [my-mod.util.log :as log]))

;; Stub: Item Energy Manager
(defonce item-energy-manager
  (reify
    Object
    (toString [_] "StubItemEnergyManager")))

(defn is-energy-item-supported?
  "Check if an item stack supports energy storage (stub)"
  [item-stack]
  ;; Stub: Assume items with 'battery' in name support energy
  (when item-stack
    (let [item-name (str item-stack)]
      (or (.contains item-name "battery")
          (.contains item-name "crystal")
          (.contains item-name "cell")))))

(defn pull-energy-from-item
  "Pull energy from an item stack (stub)
   Returns: amount of energy actually pulled"
  [item-stack amount simulate?]
  (if (is-energy-item-supported? item-stack)
    (do
      (when-not simulate?
        (log/info "Pulling" amount "energy from item" item-stack))
      ;; Stub: Return 80% of requested amount
      (* amount 0.8))
    0.0))

(defn charge-energy-to-item
  "Charge energy to an item stack (stub)
   Returns: amount of energy NOT charged (leftover)"
  [item-stack amount]
  (if (is-energy-item-supported? item-stack)
    (do
      (log/info "Charging" amount "energy to item" item-stack)
      ;; Stub: Successfully charge 80%, return 20% leftover
      (* amount 0.2))
    amount))

;; Stub: Wireless Network
(defrecord StubWirelessNetwork [id name nodes])

(defonce wireless-networks (atom {}))

(defn get-wireless-network
  "Get the wireless network for a node (stub)
   Returns: WirelessNetwork or nil if not connected"
  [node-pos node-password]
  ;; Stub: Simple logic - if password is not empty, consider connected
  (when (and node-password (not (empty? node-password)))
    (let [network-id (str "net-" node-password)]
      (or (get @wireless-networks network-id)
          (let [new-net (->StubWirelessNetwork network-id node-password [])]
            (swap! wireless-networks assoc network-id new-net)
            new-net)))))

(defn is-node-connected?
  "Check if a node is connected to wireless network (stub)"
  [node-pos node-password]
  (some? (get-wireless-network node-pos node-password)))

;; Energy transfer stub
(defn transfer-energy-wireless
  "Transfer energy through wireless network (stub)
   Returns: amount successfully transferred"
  [from-node to-node amount]
  (log/info "Wireless energy transfer:" amount "units from" from-node "to" to-node)
  ;; Stub: 90% efficiency
  (* amount 0.9))

;; Network message stub (for client-server sync)
(defn send-sync-message
  "Send synchronization message to nearby players (stub)"
  [tile-entity range data]
  (log/info "Syncing tile entity data to clients within" range "blocks:" data)
  ;; Stub: In real implementation, this would use Minecraft's packet system
  nil)
