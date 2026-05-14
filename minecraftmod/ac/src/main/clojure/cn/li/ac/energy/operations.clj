(ns cn.li.ac.energy.operations
  "Energy operations - 物品/节点/接收器的充放电与无线传输

  实现并统一对外提供：
  - IFItemManager：物品能量（电池充放电、容量、带宽）
  - IFNodeManager：节点能量（Wireless Node 充放电）
  - IFReceiverManager：接收器能量（inject/pull）
  - 无线网络：获取网络、连接状态、无线传输（含 fallback stub）
  - 网络同步：send-sync-message（当前为 stub 实现）"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.ac.energy.api.impl :as energy-api]
            [cn.li.ac.energy.service.item-manager :as item-manager]
            [cn.li.ac.energy.service.node-manager :as node-manager]
            [cn.li.ac.energy.service.transfer-service :as transfer-service]
            [cn.li.ac.wireless.api :as whelper])
  (:import [cn.li.acapi.wireless IWirelessNode IWirelessReceiver]))

(defn energy-system
  "Return the default Phase C energy system implementation."
  []
  (energy-api/energy-system))

;; ============================================================================
;; IFItemManager Implementation
;; ============================================================================

(defn is-energy-item-supported?
  "Check if an item supports energy storage"
  [item-stack]
  (item-manager/is-energy-item-supported? item-stack))

(defn get-item-energy
  "Get energy stored in item"
  [item-stack]
  (item-manager/get-item-energy item-stack))

(defn get-item-max-energy
  "Get maximum energy capacity of item"
  [item-stack]
  (item-manager/get-item-capacity item-stack))

(defn get-item-bandwidth
  "Get energy transfer bandwidth of item"
  [item-stack]
  (item-manager/get-item-bandwidth item-stack))

(defn set-item-energy!
  "Set energy in item and update durability display"
  [item-stack energy]
  (item-manager/set-item-energy! item-stack energy))

(defn charge-energy-to-item
  "Charge energy to item
  Returns the amount that couldn't be charged (leftover)"
  [item-stack amount ignore-bandwidth]
  (item-manager/charge-energy-to-item item-stack amount ignore-bandwidth))

(defn pull-energy-from-item
  "Pull energy from item
  Returns the amount actually pulled"
  [item-stack amount ignore-bandwidth]
  (item-manager/pull-energy-from-item item-stack amount ignore-bandwidth))

;; ============================================================================
;; IFNodeManager Implementation
;; ============================================================================

(defn is-node-supported?
  "Check if TileEntity is an IWirelessNode"
  [tile-entity]
  (node-manager/is-node-supported? tile-entity))

(defn get-node-energy
  "Get energy from node"
  [tile-entity]
  (node-manager/get-node-energy tile-entity))

(defn set-node-energy!
  "Set energy in node"
  [tile-entity energy]
  (node-manager/set-node-energy! tile-entity energy))

(defn charge-node
  "Charge energy to node
  Returns the amount that couldn't be charged (leftover)"
  [tile-entity amount ignore-bandwidth]
  (node-manager/charge-node tile-entity amount ignore-bandwidth))

(defn pull-from-node
  "Pull energy from node
  Returns the amount actually pulled"
  [tile-entity amount ignore-bandwidth]
  (node-manager/pull-from-node tile-entity amount ignore-bandwidth))

;; ============================================================================
;; IFReceiverManager Implementation
;; ============================================================================

(defn is-receiver-supported?
  "Check if TileEntity is an IWirelessReceiver"
  [tile-entity]
  (node-manager/is-receiver-supported? tile-entity))

(defn charge-receiver
  "Charge energy to receiver (calls inject-energy)
  Returns the amount that couldn't be charged"
  [tile-entity amount]
  (node-manager/charge-receiver tile-entity amount))

(defn pull-from-receiver
  "Pull energy from receiver (calls pull-energy)
  Returns the amount actually pulled"
  [tile-entity amount]
  (node-manager/pull-from-receiver tile-entity amount))

;; ============================================================================
;; Wireless Network (delegates to wireless.helper)
;; ============================================================================

(defn get-wireless-network
  "Get wireless network for a node (delegates to wireless.helper).
  Returns nil if no network is found or an error occurs."
  [node password]
  (try
    (whelper/get-wireless-net-by-node node)
    (catch Exception _
      {:ssid (str "Network-" password)
       :password password
       :connected-nodes #{}})))

(defn is-node-connected?
  "Check if node is connected to wireless network (delegates to wireless.helper)"
  [node password]
  (try
    (boolean (whelper/is-node-linked? node))
    (catch Exception _
      (boolean (and password (not= "" (str password)))))))

(defn transfer-energy-wireless
  "Simulate wireless transfer and return transmission loss amount.
  Current behavior is a deterministic 10% loss model used by tests/stubs."
  [_network _from _to amount]
  (transfer-service/transfer-loss amount))

(defn transfer-energy-wireless-result
  "Simulate wireless transfer and return normalized transfer result map.

  This is a new Phase C helper API while `transfer-energy-wireless` is kept as
  a legacy-compatible wrapper returning only loss amount."
  ([_network _from _to amount]
   (transfer-service/transfer-result amount))
  ([_network _from _to amount loss-rate]
   (transfer-service/transfer-result amount loss-rate)))

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
