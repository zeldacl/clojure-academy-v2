(ns cn.li.ac.block.wireless-node.handlers
  "Wireless Node network message handlers."
  (:require [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.block.wireless-node.network-infra :as infra]
            [cn.li.ac.block.wireless-node.network-presenter :as presenter]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Message ID Helper
;; ============================================================================

(defn- msg [action] (msg-registry/msg :node action))

;; ============================================================================
;; Network Message Handlers
;; ============================================================================

(defn handle-get-status
  [payload player]
  (let [{:keys [tile]} (infra/resolve-world-tile payload player)]
    (if tile
      (if-let [net (infra/linked-network tile)]
        {:linked (presenter/linked->dto net)}
        {:linked nil})
      {:linked false})))

;; Network handlers for change-name and change-password are auto-generated from schema
(def network-handlers
  (state-schema/build-network-handlers node-schema/network-editable-fields))

(def handle-change-name (get network-handlers :change-name))
(def handle-change-password (get network-handlers :change-password))

(defn handle-list-networks
  [payload player]
  (let [{:keys [world tile]} (infra/resolve-world-tile payload player)]
    (if tile
  (let [linked (infra/linked-network tile)
            linked-ssid (when linked (wireless-api/network-ssid linked))
            x (double (:pos-x payload))
            y (double (:pos-y payload))
            z (double (:pos-z payload))
    range (infra/node-range tile)
    avail (infra/available-networks world x y z range)]
    (presenter/list-networks-response
      {:linked linked
       :avail avail
       :linked-ssid linked-ssid
       :matrix-cap-fn (fn [net] (infra/matrix-capability world net))
       :matrix-capacity infra/matrix-capacity
       :matrix-bandwidth infra/matrix-bandwidth
       :matrix-range infra/matrix-range}))
      {:linked nil :avail []})))

(defn handle-connect
  [payload player]
  (let [{:keys [world tile]} (infra/resolve-world-tile payload player)
        ssid (:ssid payload)
        password (:password payload)]
    (if (and world tile ssid)
      {:success (infra/connect-node! world tile ssid password)}
      {:success false})))

(defn handle-disconnect
  [payload player]
  (let [{:keys [world tile]} (infra/resolve-world-tile payload player)]
    (if (and world tile)
      {:success (infra/disconnect-node! tile)}
      {:success false})))

;; ============================================================================
;; Handler Registration
;; ============================================================================

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :change-name) handle-change-name)
  (net-server/register-handler (msg :change-password) handle-change-password)
  (net-server/register-handler (msg :list-networks) handle-list-networks)
  (net-server/register-handler (msg :connect) handle-connect)
  (net-server/register-handler (msg :disconnect) handle-disconnect)
  (log/info "Node GUI network handlers registered"))
