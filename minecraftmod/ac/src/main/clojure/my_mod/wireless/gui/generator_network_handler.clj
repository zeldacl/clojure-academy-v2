(ns my-mod.wireless.gui.generator-network-handler
  "Wireless generator GUI - server-side network handlers (SolarGen etc.)."
  (:require [my-mod.network.server :as net-server]
            [my-mod.wireless.helper :as helper]
            [my-mod.wireless.virtual-blocks :as vb]
            [my-mod.wireless.gui.generator-messages :as gen-msgs]
            [my-mod.wireless.gui.wireless-messages :as wireless-msgs]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.wireless.node-connection :as node-conn]
            [my-mod.platform.position :as pos]
            [my-mod.util.log :as log])
  (:import [my_mod.api.wireless IWirelessNode]))

(defn handle-get-status
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [conn (try (helper/get-node-conn-by-generator tile) (catch Exception _ nil))
            ^IWirelessNode node (when conn (try (node-conn/get-node conn) (catch Exception _ nil)))
            node-pos (when node (.getBlockPos node))
            pw (when node (try (str (.getPassword node)) (catch Exception _ "")))]
        {:linked (when node
                   {:node-name (try (str (.getNodeName node)) (catch Exception _ "Node"))
                    :pos-x (when node-pos (pos/pos-x node-pos))
                    :pos-y (when node-pos (pos/pos-y node-pos))
                    :pos-z (when node-pos (pos/pos-z node-pos))
                    :is-encrypted? (not (empty? pw))})
         :avail []})
      {:linked nil :avail []})))

(defn handle-list-nodes
  [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [tile-pos (pos/position-get-block-pos tile)
            linked-conn (try (helper/get-node-conn-by-generator tile) (catch Exception _ nil))
            ^IWirelessNode linked-node (when linked-conn (try (node-conn/get-node linked-conn) (catch Exception _ nil)))
            linked-pos (when linked-node (.getBlockPos linked-node))
            nodes (if tile-pos (helper/get-nodes-in-range world tile-pos) [])
            linked (when linked-node
                     (let [pw (try (str (.getPassword linked-node)) (catch Exception _ ""))]
                       {:node-name (try (str (.getNodeName linked-node)) (catch Exception _ "Node"))
                        :pos-x (when linked-pos (pos/pos-x linked-pos))
                        :pos-y (when linked-pos (pos/pos-y linked-pos))
                        :pos-z (when linked-pos (pos/pos-z linked-pos))
                        :is-encrypted? (not (empty? pw))}))
            avail (->> nodes
                       (remove (fn [^IWirelessNode node]
                                 (let [p (.getBlockPos node)]
                                   (and p linked-pos
                                        (= (pos/pos-x p) (pos/pos-x linked-pos))
                                        (= (pos/pos-y p) (pos/pos-y linked-pos))
                                        (= (pos/pos-z p) (pos/pos-z linked-pos))))))
                       (mapv (fn [^IWirelessNode node]
                               (let [p (.getBlockPos node)
                                     pw (try (str (.getPassword node)) (catch Exception _ ""))]
                                 {:node-name (try (str (.getNodeName node)) (catch Exception _ "Node"))
                                  :pos-x (when p (pos/pos-x p))
                                  :pos-y (when p (pos/pos-y p))
                                  :pos-z (when p (pos/pos-z p))
                                  :is-encrypted? (not (empty? pw))}))))]
        {:linked linked
         :avail avail})
      {:linked nil :avail []})))

(defn handle-connect
  [payload player]
  (let [world (net-helpers/get-world player)
        gen (net-helpers/get-tile-at world payload)
        node-pos (select-keys payload [:node-x :node-y :node-z])
        pass (:password payload "")]
    (if (and world gen (every? number? (vals node-pos)))
      (let [node (net-helpers/get-tile-at world {:pos-x (:node-x node-pos)
                                                 :pos-y (:node-y node-pos)
                                                 :pos-z (:node-z node-pos)})
            need-auth? (boolean (:need-auth? payload true))]
        (if node
          {:success (boolean (helper/link-generator-to-node! gen node pass need-auth?))}
          {:success false}))
      {:success false})))

(defn handle-disconnect
  [payload player]
  (let [world (net-helpers/get-world player)
        gen (net-helpers/get-tile-at world payload)]
    (if (and world gen)
      (do
        (helper/unlink-generator-from-node! gen)
        {:success true})
      {:success false})))

(defn register-handlers! []
  (net-server/register-handler (gen-msgs/msg :get-status) handle-get-status)
  (net-server/register-handler (gen-msgs/msg :list-nodes) handle-list-nodes)
  (net-server/register-handler (gen-msgs/msg :connect) handle-connect)
  (net-server/register-handler (gen-msgs/msg :disconnect) handle-disconnect)
  (log/info "Generator GUI network handlers registered"))

(defn init! []
  (:specs wireless-msgs/catalog)
  (register-handlers!))

