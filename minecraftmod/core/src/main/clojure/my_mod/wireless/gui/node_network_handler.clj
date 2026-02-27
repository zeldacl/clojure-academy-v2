(ns my-mod.wireless.gui.node-network-handler
  "Wireless Node GUI - server-side network handlers"
  (:require [my-mod.network.server :as net-server]
            [my-mod.wireless.helper :as helper]
            [my-mod.wireless.network :as wireless-net]
            [my-mod.wireless.world-data :as world-data]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.block.wireless-node :as node]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log]))

(def ^:const MSG_GET_STATUS "wireless_node_get_status")
(def ^:const MSG_CHANGE_NAME "wireless_node_change_name")
(def ^:const MSG_CHANGE_PASSWORD "wireless_node_change_password")
(def ^:const MSG_LIST_NETWORKS "wireless_node_list_networks")
(def ^:const MSG_CONNECT "wireless_node_connect")
(def ^:const MSG_DISCONNECT "wireless_node_disconnect")

(defn- update-node-field!
  [tile field value]
  (case field
    :node-name (node/set-node-name! tile value)
    :password (node/set-password-str! tile value)
    tile))

(defn handle-get-status
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      {:linked (boolean (helper/is-node-linked? tile))}
      {:linked false})))

(defn handle-change-name
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-name (:node-name payload)]
    (if (and tile new-name)
      (do
        (update-node-field! tile :node-name new-name)
        {:success true})
      {:success false})))

(defn handle-change-password
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-password (:password payload)]
    (if (and tile new-password)
      (do
        (update-node-field! tile :password new-password)
        {:success true})
      {:success false})))

(defn handle-list-networks
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [pos (.getPos tile)
            range (try (.getRange tile) (catch Exception _ 20.0))
            nets (helper/get-nets-in-range world (.getX pos) (.getY pos) (.getZ pos) range 100)]
        {:networks (mapv (fn [net]
                           (let [matrix (when (:matrix net)
                                         (vb/vblock-get (:matrix net) world))]
                             {:ssid (:ssid net)
                              :load (wireless-net/get-load net)
                              :capacity (if matrix
                                         (try (winterfaces/get-capacity matrix) (catch Exception _ 0))
                                         0)
                              :is-encrypted? (not (empty? (str (:password net))))
                              :bandwidth (if matrix
                                          (try (winterfaces/get-bandwidth matrix) (catch Exception _ 0))
                                          0)
                              :range (if matrix
                                      (try (winterfaces/get-range matrix) (catch Exception _ 0.0))
                                      0.0)}))
                         nets)})
      {:networks []})))

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

(defn register-handlers! []
  (net-server/register-handler MSG_GET_STATUS handle-get-status)
  (net-server/register-handler MSG_CHANGE_NAME handle-change-name)
  (net-server/register-handler MSG_CHANGE_PASSWORD handle-change-password)
  (net-server/register-handler MSG_LIST_NETWORKS handle-list-networks)
  (net-server/register-handler MSG_CONNECT handle-connect)
  (net-server/register-handler MSG_DISCONNECT handle-disconnect)
  (log/info "Node GUI network handlers registered"))

(defn init! []
  (register-handlers!))
