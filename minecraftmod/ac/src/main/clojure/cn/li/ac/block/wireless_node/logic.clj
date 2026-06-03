(ns cn.li.ac.block.wireless-node.logic
  "Wireless Node block event handlers (placement, break, GUI open)."
  (:require [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.wireless-node.owner :as node-owner]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.mcmod.platform.world :as platform-world]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as ppos]
            [cn.li.mcmod.util.log :as log]))

(defn handle-node-right-click
  [_node-type]
  (machine-runtime/make-open-gui-handler :node))

(defn handle-node-place
  [node-type]
  (fn [{:keys [player world pos]}]
    (log/info "Placing Wireless Node (" (name node-type) ")")
    (let [player-name (node-owner/player-name player)
          node-vb (vb/create-vnode (ppos/pos-x pos) (ppos/pos-y pos) (ppos/pos-z pos))
          be (platform-world/world-get-tile-entity* world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-state/node-default-state)
              state' (assoc state :node-type node-type :placer-name player-name)]
          (machine-runtime/commit-state! be world pos state state')))
      (try
        (wireless-api/register-node-spatial! world node-vb)
        (catch Exception _))
      (log/info "Node placed by" player-name "at" pos))))

(defn handle-node-break
  [_node-type]
  (fn [{:keys [world pos]}]
    (log/info "Breaking Wireless Node")
    (let [node-vb (vb/create-vnode (ppos/pos-x pos) (ppos/pos-y pos) (ppos/pos-z pos))
          be (platform-world/world-get-tile-entity* world pos)]
      (when be
        (let [state (or (platform-be/get-custom-state be) node-state/node-default-state)]
          (doseq [item (:inventory state [])]
            (when item (log/info "Dropping item:" item)))))
      (try
        (wireless-api/unregister-node-spatial! world node-vb)
        (when be
          (wireless-api/unlink-node-from-network! be)
          (wireless-api/destroy-node-connection-for-node! be))
        (catch Exception _)))))
