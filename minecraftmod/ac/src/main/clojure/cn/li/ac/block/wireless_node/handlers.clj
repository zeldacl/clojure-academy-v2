(ns cn.li.ac.block.wireless-node.handlers
  "Wireless Node network message handlers."
  (:require [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.block.wireless-node.config :as node-config]
            [cn.li.ac.wireless.data.world :as world-data]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.search-config :as search-config]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.api :as helper]
            [cn.li.ac.wireless.data.network :as wireless-net]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.block.wireless-node.logic :as logic]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessNode IWirelessMatrix WirelessCapabilityKeys]))

;; ============================================================================
;; Message ID Helper
;; ============================================================================

(defn- msg [action] (msg-registry/msg :node action))

;; ============================================================================
;; Network Message Handlers
;; ============================================================================

(defn handle-get-status
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (if-let [net (helper/get-wireless-net-by-node tile)]
        {:linked {:ssid (:ssid net)
                  :is-encrypted? (not (empty? (str (:password net))))}}
        {:linked nil})
      {:linked false})))

;; Network handlers for change-name and change-password are auto-generated from schema
(def network-handlers
  (state-schema/build-network-handlers node-schema/network-editable-fields))

(def handle-change-name (get network-handlers :change-name))
(def handle-change-password (get network-handlers :change-password))

(defn handle-list-networks
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [linked (try (helper/get-wireless-net-by-node tile) (catch Exception _ nil))
            linked-ssid (when linked (:ssid linked))
            x (double (:pos-x payload))
            y (double (:pos-y payload))
            z (double (:pos-z payload))
            range (try (.getRange ^IWirelessNode tile) (catch Exception _ 20.0))
            nets (helper/get-nets-in-range world x y z range (search-config/max-results))]
        {:linked (when linked
                   {:ssid (:ssid linked)
                    :is-encrypted? (not (empty? (str (:password linked))))})
         :avail (->> nets
                     (remove (fn [net] (= (:ssid net) linked-ssid)))
                     (mapv (fn [net]
                             (let [matrix (when (:matrix net)
                     (vb/vblock-get (:matrix net) world))
                  matrix-cap (when matrix
                                                 (platform-be/get-capability matrix WirelessCapabilityKeys/MATRIX))]
              {:ssid (:ssid net)
               :is-encrypted? (not (empty? (str (:password net))))
               :load (wireless-net/get-load net)
               :capacity (if matrix-cap
                     (try (.getMatrixCapacity ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
                     0)
               :bandwidth (if matrix-cap
                      (try (.getMatrixBandwidth ^IWirelessMatrix matrix-cap) (catch Exception _ 0))
                      0)
               :range (if matrix-cap
                  (try (.getMatrixRange ^IWirelessMatrix matrix-cap) (catch Exception _ 0.0))
                  0.0)}))))})
      {:linked nil :avail []})))

(defn handle-connect
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        ssid (:ssid payload)
        password (:password payload)]
    (if (and world tile ssid)
      (let [world-data (world-data/get-world-data world)
            net (world-data/get-network-by-ssid world-data ssid)
            matrix (when net (vb/vblock-get (:matrix net) world))]
        (if (and net matrix)
          {:success (boolean (wireless-net/add-node! net (vb/create-vnode tile) password))}
          {:success false}))
      {:success false})))

(defn handle-disconnect
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if (and world tile)
      (do
        (helper/unlink-node-from-network! tile)
        {:success true})
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
