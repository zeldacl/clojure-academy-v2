(ns cn.li.ac.block.wireless-node.handlers
  "Wireless Node network message handlers and infrastructure."
  (:require [clojure.string :as str]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.ac.wireless.config :as search-config]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessNode IWirelessMatrix]))

;; ============================================================================
;; Infrastructure (from network_infra.clj)
;; ============================================================================

(defn resolve-world-tile
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    {:world world :tile tile}))

(defn linked-network
  [tile]
  (try
    (wireless-api/get-wireless-net-by-node tile)
    (catch Exception _ nil)))

(defn node-range
  [tile]
  (try
    (if-let [node-cap (resolver/node-capability tile)]
      (.getRange ^IWirelessNode node-cap)
      20.0)
    (catch Exception _ 20.0)))

(defn available-networks
  [world x y z range]
  (wireless-api/get-nets-in-range world x y z range (search-config/max-results)))

(defn matrix-capability
  [world net]
  (when (:matrix net)
    (resolver/resolve-matrix-cap world (:matrix net))))

(defn matrix-capacity
  [matrix-cap]
  (if matrix-cap
    (try (.getMatrixCapacity ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
    0))

(defn matrix-bandwidth
  [matrix-cap]
  (if matrix-cap
    (try (.getMatrixBandwidth ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
    0))

(defn matrix-range
  [matrix-cap]
  (if matrix-cap
    (try (.getMatrixRange ^IWirelessMatrix matrix-cap) (catch Exception _ 0.0))
    0.0))

(defn connect-node!
  [world tile ssid password]
  (wireless-api/connect-node-to-ssid! world tile ssid password))

(defn disconnect-node!
  [tile]
  (wireless-api/unlink-node-from-network! tile)
  true)

;; ============================================================================
;; Response DTO builders (from network_presenter.clj)
;; ============================================================================

(defn linked->dto
  [linked]
  (when linked
    (let [{:keys [ssid password]} (wireless-api/network-snapshot linked)]
      {:ssid ssid
       :is-encrypted? (not (empty? (str password)))})))

(defn available-net->dto
  [net matrix-cap {:keys [matrix-capacity matrix-bandwidth matrix-range]}]
  (let [{:keys [ssid password load]} (wireless-api/network-snapshot net)]
    {:ssid ssid
     :is-encrypted? (not (empty? (str password)))
     :load load
     :capacity (matrix-capacity matrix-cap)
     :bandwidth (matrix-bandwidth matrix-cap)
     :range (matrix-range matrix-cap)}))

(defn list-networks-response
  [{:keys [linked avail linked-ssid matrix-cap-fn matrix-capacity matrix-bandwidth matrix-range]}]
  {:linked (linked->dto linked)
   :avail (->> avail
               (remove (fn [net] (= (wireless-api/network-ssid net) linked-ssid)))
               (mapv (fn [net]
                       (let [matrix-cap (matrix-cap-fn net)]
                         (available-net->dto
                           net
                           matrix-cap
                           {:matrix-capacity matrix-capacity
                            :matrix-bandwidth matrix-bandwidth
                            :matrix-range matrix-range})))))})

;; ============================================================================
;; Message ID helper
;; ============================================================================

(defn- msg
  [action]
  (msg-registry/msg :node action))

;; ============================================================================
;; Authorization helpers
;; ============================================================================

(defn- owner-authorized?
  [tile player]
  (let [owner (node-logic/owner-name (platform-be/get-custom-state tile))]
    (node-logic/owner-authorized? owner player)))

(defn- with-owner-authorization
  [payload player handler-fn]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if (and tile (owner-authorized? tile player))
      (handler-fn payload player)
      {:success false})))

;; ============================================================================
;; Handler functions
;; ============================================================================

(defn handle-get-status
  [payload player]
  (let [{:keys [tile]} (resolve-world-tile payload player)]
    (if tile
      (if-let [net (linked-network tile)]
        {:linked (linked->dto net)}
        {:linked nil})
      {:linked false})))

;; Network handlers for change-name and change-password are auto-generated from schema
(def generated-network-handlers
  (state-schema/build-network-handlers node-schema/network-editable-fields))

(defn handle-change-name
  [payload player]
  (with-owner-authorization payload player (get generated-network-handlers :change-name)))

(defn handle-change-password
  [payload player]
  (with-owner-authorization payload player (get generated-network-handlers :change-password)))

(defn handle-list-networks
  [payload player]
  (let [{:keys [world tile]} (resolve-world-tile payload player)]
    (if tile
      (let [linked (linked-network tile)
            linked-ssid (when linked (wireless-api/network-ssid linked))
            x (double (:pos-x payload))
            y (double (:pos-y payload))
            z (double (:pos-z payload))
            range (node-range tile)
            avail (available-networks world x y z range)]
        (list-networks-response
          {:linked linked
           :avail avail
           :linked-ssid linked-ssid
           :matrix-cap-fn (fn [net] (matrix-capability world net))
           :matrix-capacity matrix-capacity
           :matrix-bandwidth matrix-bandwidth
           :matrix-range matrix-range}))
      {:linked nil :avail []})))

(defn handle-connect
  [payload player]
  (let [{:keys [world tile]} (resolve-world-tile payload player)
        ssid (:ssid payload)
        password (:password payload)]
    (if (and world tile ssid)
      {:success (connect-node! world tile ssid password)}
      {:success false})))

(defn handle-disconnect
  [payload player]
  (let [{:keys [world tile]} (resolve-world-tile payload player)]
    (if (and world tile)
      {:success (disconnect-node! tile)}
      {:success false})))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-network-handlers!
  []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :change-name) handle-change-name)
  (net-server/register-handler (msg :change-password) handle-change-password)
  (net-server/register-handler (msg :list-networks) handle-list-networks)
  (net-server/register-handler (msg :connect) handle-connect)
  (net-server/register-handler (msg :disconnect) handle-disconnect)
  (log/info "Node GUI network handlers registered"))
