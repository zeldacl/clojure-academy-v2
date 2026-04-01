(ns cn.li.ac.energy.operations
  "Energy operations - 物品/节点/接收器的充放电与无线传输

  实现并统一对外提供：
  - IFItemManager：物品能量（电池充放电、容量、带宽）
  - IFNodeManager：节点能量（Wireless Node 充放电）
  - IFReceiverManager：接收器能量（inject/pull）
  - 无线网络：获取网络、连接状态、无线传输（含 fallback stub）
  - 网络同步：send-sync-message（当前为 stub 实现）"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.ac.energy.imag-energy-item :as energy-item]
            [cn.li.ac.item.test-battery :as battery]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.ac.wireless.api :as whelper])
  (:import [cn.li.acapi.wireless IWirelessNode IWirelessReceiver]))

;; ============================================================================
;; IFItemManager Implementation
;; ============================================================================

(defn is-energy-item-supported?
  "Check if an item supports energy storage"
  [item-stack]
  (when item-stack
    (battery/is-battery? item-stack)))

(defn get-item-energy
  "Get energy stored in item"
  [item-stack]
  (if (battery/is-battery? item-stack)
    (battery/get-battery-energy item-stack)
    0.0))

(defn get-item-max-energy
  "Get maximum energy capacity of item"
  [item-stack]
  (if (battery/is-battery? item-stack)
    (battery/get-max-battery-energy item-stack)
    0.0))

(defn get-item-bandwidth
  "Get energy transfer bandwidth of item"
  [item-stack]
  (if (battery/is-battery? item-stack)
    (battery/get-battery-bandwidth item-stack)
    0.0))

(defn set-item-energy!
  "Set energy in item and update durability display"
  [item-stack energy]
  (when (battery/is-battery? item-stack)
    (battery/set-battery-energy! item-stack energy)))

(defn charge-energy-to-item
  "Charge energy to item
  Returns the amount that couldn't be charged (leftover)"
  [item-stack amount ignore-bandwidth]
  (if (battery/is-battery? item-stack)
    (battery/charge-battery! item-stack amount ignore-bandwidth)
    amount))

(defn pull-energy-from-item
  "Pull energy from item
  Returns the amount actually pulled"
  [item-stack amount ignore-bandwidth]
  (if (battery/is-battery? item-stack)
    (battery/pull-from-battery! item-stack amount ignore-bandwidth)
    0.0))

;; ============================================================================
;; IFNodeManager Implementation
;; ============================================================================

(defn is-node-supported?
  "Check if TileEntity is an IWirelessNode"
  [tile-entity]
  (instance? IWirelessNode tile-entity))

(defn get-node-energy
  "Get energy from node"
  [tile-entity]
  (when (is-node-supported? tile-entity)
    (.getEnergy ^IWirelessNode tile-entity)))

(defn set-node-energy!
  "Set energy in node"
  [tile-entity energy]
  (when (is-node-supported? tile-entity)
    (.setEnergy ^IWirelessNode tile-entity energy)))

(defn charge-node
  "Charge energy to node
  Returns the amount that couldn't be charged (leftover)"
  [tile-entity amount ignore-bandwidth]
  (if (is-node-supported? tile-entity)
    (let [node       ^IWirelessNode tile-entity
          current    (.getEnergy node)
          max-energy (.getMaxEnergy node)
          bandwidth  (.getBandwidth node)
          space (- max-energy current)
          limit (if ignore-bandwidth Double/MAX_VALUE bandwidth)
          to-charge (min amount space limit)
          leftover (- amount to-charge)]
      (.setEnergy node (+ current to-charge))
      leftover)
    amount))

(defn pull-from-node
  "Pull energy from node
  Returns the amount actually pulled"
  [tile-entity amount ignore-bandwidth]
  (if (is-node-supported? tile-entity)
    (let [node      ^IWirelessNode tile-entity
          current   (.getEnergy node)
          bandwidth (.getBandwidth node)
          limit (if ignore-bandwidth Double/MAX_VALUE bandwidth)
          to-pull (min amount current limit)]
      (.setEnergy node (- current to-pull))
      to-pull)
    0.0))

;; ============================================================================
;; IFReceiverManager Implementation
;; ============================================================================

(defn is-receiver-supported?
  "Check if TileEntity is an IWirelessReceiver"
  [tile-entity]
  (instance? IWirelessReceiver tile-entity))

(defn charge-receiver
  "Charge energy to receiver (calls inject-energy)
  Returns the amount that couldn't be charged"
  [tile-entity amount]
  (if (is-receiver-supported? tile-entity)
    (.injectEnergy ^IWirelessReceiver tile-entity amount)
    amount))

(defn pull-from-receiver
  "Pull energy from receiver (calls pull-energy)
  Returns the amount actually pulled"
  [tile-entity amount]
  (if (is-receiver-supported? tile-entity)
    (.pullEnergy ^IWirelessReceiver tile-entity amount)
    0.0))

;; ============================================================================
;; Wireless Network (delegates to wireless.helper; fallback stub when unavailable)
;; ============================================================================

(defrecord StubWirelessNetwork
  [ssid password node-list])

(defn get-wireless-network
  "Get wireless network for a node (delegates to wireless.helper)"
  [node password]
  (try
    (whelper/get-wireless-net-by-node node)
    (catch Exception e
      (log/info (format "Using stub wireless network for node with password: %s" password))
      (->StubWirelessNetwork
        (str "Network-" password)
        password
        []))))

(defn is-node-connected?
  "Check if node is connected to wireless network (delegates to wireless.helper)"
  [node password]
  (try
    (whelper/is-node-linked? node)
    (catch Exception e
      (not (empty? password)))))

(defn transfer-energy-wireless
  "Transfer energy through wireless network (stub implementation)
  Returns the amount that couldn't be transferred"
  [network from-node to-node amount]
  (try
    (let [actual-transfer (* amount 0.9)
          leftover (* amount 0.1)]
      (log/info (format "Wireless transfer: %.1f energy, %.1f loss (simulated)"
                        actual-transfer leftover))
      leftover)
    (catch Exception e
      (log/info (format "Wireless transfer error: %s"(ex-message e)))
      amount)))

;; ============================================================================
;; Network Synchronization (stub: logs only)
;; ============================================================================

(defn send-sync-message
  "Send synchronization message to client (stub implementation)"
  [player data]
  (log/info (format "Sync to %s: enabled=%s, chargingIn=%s, chargingOut=%s, energy=%.1f"
                    player
                    (:enabled data)
                    (:charging-in data)
                    (:charging-out data)
                    (:energy data))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-energy-system! []
  (log/info "Energy system initialized"))
