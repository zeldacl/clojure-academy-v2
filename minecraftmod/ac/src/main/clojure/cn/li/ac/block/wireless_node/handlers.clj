(ns cn.li.ac.block.wireless-node.handlers
  "Wireless Node network message handlers."
  (:require [clojure.string :as str]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.block.wireless-node.network-infra :as infra]
            [cn.li.ac.block.wireless-node.network-presenter :as presenter]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
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
(def generated-network-handlers
  (state-schema/build-network-handlers node-schema/network-editable-fields))

(defn- owner-authorized?
  [tile player]
  (let [owner (str (get (or (platform-be/get-custom-state tile) {}) :placer-name ""))
        player-name (str (entity/player-get-name player))]
    (or (str/blank? owner)
        (= owner player-name))))

(defn- with-owner-authorization
  [payload player handler-fn]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if (and tile (owner-authorized? tile player))
      (handler-fn payload player)
      {:success false})))

(defn handle-change-name
  [payload player]
  (with-owner-authorization payload player (get generated-network-handlers :change-name)))

(defn handle-change-password
  [payload player]
  (with-owner-authorization payload player (get generated-network-handlers :change-password)))

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
