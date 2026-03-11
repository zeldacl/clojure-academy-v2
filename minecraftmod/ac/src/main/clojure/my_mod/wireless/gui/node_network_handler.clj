(ns my-mod.wireless.gui.node-network-handler
  "Wireless Node GUI - server-side network handlers"
  (:require [my-mod.network.server :as net-server]
            [my-mod.wireless.helper :as helper]
            [my-mod.wireless.network :as wireless-net]
            [my-mod.wireless.world-data :as world-data]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.wireless.interfaces :as winterfaces]
            [my-mod.wireless.gui.node-messages :as node-msgs]
            [my-mod.wireless.gui.wireless-messages :as wireless-msgs]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log]))

(defn- update-node-field!
  "Update a single field in the BE's customState (Design-3).
  be must be a ScriptedBlockEntity whose customState is the node state map."
  [be field value]
  (let [state (or (.getCustomState be) {})]
    (.setCustomState be (assoc state field value))
    (try (.setChanged be) (catch Exception _))
    be))

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
      (let [;; Use coordinates from the payload directly — avoids calling
            ;; getPos() on ScriptedBlockEntity (renamed getBlockPos() in 1.17+)
            x (double (:pos-x payload))
            y (double (:pos-y payload))
            z (double (:pos-z payload))
            range (try (.getRange tile) (catch Exception _ 20.0))
            nets (helper/get-nets-in-range world x y z range 100)]
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
  (net-server/register-handler (node-msgs/msg :get-status) handle-get-status)
  (net-server/register-handler (node-msgs/msg :change-name) handle-change-name)
  (net-server/register-handler (node-msgs/msg :change-password) handle-change-password)
  (net-server/register-handler (node-msgs/msg :list-networks) handle-list-networks)
  (net-server/register-handler (node-msgs/msg :connect) handle-connect)
  (net-server/register-handler (node-msgs/msg :disconnect) handle-disconnect)
  (log/info "Node GUI network handlers registered"))

(defn init! []
  ;; Touching catalog ensures cross-domain validation runs at init time.
  (:specs wireless-msgs/catalog)
  (register-handlers!))
