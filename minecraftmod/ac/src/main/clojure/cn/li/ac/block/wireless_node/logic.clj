(ns cn.li.ac.block.wireless-node.logic
  "Stable entry point for Wireless Node block logic.

  Implementation has been split by responsibility:
  - `wireless-node.state` owns schema/default/tier/blockstate projection.
  - `wireless-node.inventory` owns slot schema and container inventory ops.
  - `wireless-node.tick` owns server tick, charge, network status, and sync.
  - `wireless-node.capability` owns Java capability implementations.

  Keep this namespace as the stable entry point used by block registration,
  schema metadata, tests, and external callers."
  (:require [cn.li.ac.block.wireless-node.capability :as node-capability]
            [cn.li.ac.block.wireless-node.inventory :as node-inventory]
            [cn.li.ac.block.wireless-node.owner :as node-owner]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.block.wireless-node.tick :as node-tick]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-membership :as network-membership]
            [cn.li.ac.wireless.service.world-registry :as world-registry]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as ppos]
            [cn.li.mcmod.platform.world :as platform-world]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; State/schema exports
;; ============================================================================

(def node-state-schema node-state/node-state-schema)
(def node-default-state node-state/node-default-state)
(def node-scripted-load-fn node-state/node-scripted-load-fn)
(def node-scripted-save-fn node-state/node-scripted-save-fn)
(def block-state-properties node-state/block-state-properties)

(def node-types node-state/node-types)
(def node-max-energy node-state/node-max-energy)
(def energy->blockstate-level node-state/energy->blockstate-level)

;; ============================================================================
;; Inventory/tick/capability exports
;; ============================================================================

(def ensure-node-slot-schema! node-inventory/ensure-node-slot-schema!)
(def node-container-fns node-inventory/node-container-fns)

(def node-scripted-tick-fn node-tick/node-scripted-tick-fn)
(def tick-charge-in node-tick/tick-charge-in)
(def tick-charge-out node-tick/tick-charge-out)
(def tick-check-network node-tick/tick-check-network)

(def ->WirelessNodeImpl node-capability/->WirelessNodeImpl)
(def ->ClojureEnergyImpl node-capability/->ClojureEnergyImpl)

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn handle-node-right-click [node-type]
  (fn [event-data]
    (log/info "Wireless Node (" (name node-type) ") right-clicked!")
    (let [{:keys [player world pos]} event-data
          be    (platform-world/world-get-tile-entity* world pos)
          state (when be (or (platform-be/get-custom-state be) node-state/node-default-state))]
      (if state
        (do
          (log/info "Node status:")
          (log/info "  Energy:" (:energy state) "/" (node-state/node-max-energy state))
          (log/info "  Connected:" (:enabled state))
          (log/info "  Name:" (:node-name state))
          (try
            (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.gui.open/open-gui-by-type)]
              (let [result (open-gui-by-type player :node world pos)]
                (log/info "Opened Node GUI")
                result)
              (do (log/error "Node GUI registry function not found") nil))
            (catch Exception e
              (log/error "Failed to open Node GUI:" (ex-message e))
              nil)))
        (log/info "No tile entity found!")))))

(defn handle-node-place [node-type]
  (fn [event-data]
    (log/info "Placing Wireless Node (" (name node-type) ")")
    (let [{:keys [player world pos]} event-data
          player-name (node-owner/player-name player)
          node-vb      (vb/create-vnode (ppos/pos-x pos) (ppos/pos-y pos) (ppos/pos-z pos))
          be          (platform-world/world-get-tile-entity* world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-state/node-default-state)]
          (platform-be/set-custom-state! be (assoc state
                                             :node-type   node-type
                                             :placer-name player-name))))
      (try
        (let [wd (world-registry/get-world-data world)]
          (world-registry/add-to-spatial-index! wd node-vb))
        (catch Exception _))
      (log/info "Node placed by" player-name "at" pos))))

(defn handle-node-break [node-type]
  (fn [event-data]
    (log/info "Breaking Wireless Node (" (name node-type) ")")
    (let [{:keys [world pos]} event-data
          node-vb (vb/create-vnode (ppos/pos-x pos) (ppos/pos-y pos) (ppos/pos-z pos))
          be      (platform-world/world-get-tile-entity* world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-state/node-default-state)]
          (doseq [item (:inventory state [])]
            (when item (log/info "Dropping item:" item)))))
      (try
        (when-let [wd (world-registry/get-world-data-non-create world)]
          (world-registry/remove-from-spatial-index! wd node-vb)
          (when-let [net (world-registry/get-network-by-node wd node-vb)]
            (network-membership/remove-node! net node-vb))
          (when-let [conn (world-registry/get-node-connection wd node-vb)]
            (world-registry/destroy-node-connection-impl! wd conn)))
        (catch Exception _)))))
