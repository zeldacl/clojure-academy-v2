(ns cn.li.ac.block.machine.wireless-handlers
  "Shared wireless generator/receiver network handlers for block GUIs."
  (:require [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessNode]))

(defn wireless-node->info
  [^IWirelessNode node]
  (when node
    (let [p (try (.getBlockPos node) (catch Exception _ nil))
          pw (try (str (.getPassword node)) (catch Exception _ ""))]
      {:node-name (try (str (.getNodeName node)) (catch Exception _ "Node"))
       :pos-x (when p (pos/pos-x p))
       :pos-y (when p (pos/pos-y p))
       :pos-z (when p (pos/pos-z p))
       :is-encrypted? (not (empty? pw))})))

(defn- same-block-pos? [p linked-pos]
  (and p linked-pos
       (= (pos/pos-x p) (pos/pos-x linked-pos))
       (= (pos/pos-y p) (pos/pos-y linked-pos))
       (= (pos/pos-z p) (pos/pos-z linked-pos))))

(defn available-nodes
  [world tile-pos linked-node]
  (let [linked-pos (when linked-node (try (.getBlockPos ^IWirelessNode linked-node) (catch Exception _ nil)))
        nodes (if tile-pos (wireless-api/get-nodes-in-range world tile-pos) [])]
    (->> nodes
         (remove (fn [^IWirelessNode n]
                   (let [p (try (.getBlockPos n) (catch Exception _ nil))]
                     (same-block-pos? p linked-pos))))
         (mapv wireless-node->info))))

(defn register-link-handlers!
  "Register list/connect/disconnect handlers for a wireless-linked machine.

  spec keys:
  - :message-domain keyword (:generator or :developer)
  - :get-linked-node (fn [tile] -> node or nil)
  - :link! (fn [tile node password need-auth?] -> bool)
  - :unlink! (fn [tile] -> nil)
  - :status-fn optional (fn [payload player tile] -> map) for :get-status
  - :extra-handlers optional map msg-keyword -> handler
  - :log-label optional string"
  [{:keys [message-domain get-linked-node link! unlink! status-fn extra-handlers log-label]}]
  (let [msg (fn [action] (msg-registry/msg message-domain action))
        handle-get-status
        (fn [payload player]
          (let [world (net-helpers/get-world player)
                tile (net-helpers/get-tile-at world payload)]
            (if (and tile status-fn)
              (status-fn payload player tile)
              (let [linked (some-> tile get-linked-node wireless-node->info)]
                {:linked linked :avail []}))))
        handle-list-nodes
        (fn [payload player]
          (let [world (net-helpers/get-world player)
                tile (net-helpers/get-tile-at world payload)]
            (if tile
              (let [tile-pos (pos/position-get-block-pos tile)
                    linked-node (get-linked-node tile)]
                {:linked (wireless-node->info linked-node)
                 :avail (available-nodes world tile-pos linked-node)})
              {:linked nil :avail []})))
        handle-connect
        (fn [payload player]
          (let [world (net-helpers/get-world player)
                device (net-helpers/get-tile-at world payload)
                node-pos (select-keys payload [:node-x :node-y :node-z])
                pass (:password payload "")
                need-auth? (boolean (:need-auth? payload true))]
            (if (and world device
                     (number? (:node-x node-pos))
                     (number? (:node-y node-pos))
                     (number? (:node-z node-pos)))
              (if-let [node (net-helpers/get-tile-at world {:pos-x (:node-x node-pos)
                                                             :pos-y (:node-y node-pos)
                                                             :pos-z (:node-z node-pos)})]
                {:success (boolean (link! device node pass need-auth?))}
                {:success false})
              {:success false})))
        handle-disconnect
        (fn [payload player]
          (let [world (net-helpers/get-world player)
                device (net-helpers/get-tile-at world payload)]
            (if (and world device)
              (do (unlink! device) {:success true})
              {:success false})))]
    (net-server/register-handler (msg :get-status) handle-get-status)
    (net-server/register-handler (msg :list-nodes) handle-list-nodes)
    (net-server/register-handler (msg :connect) handle-connect)
    (net-server/register-handler (msg :disconnect) handle-disconnect)
    (doseq [[action handler] (or extra-handlers {})]
      (net-server/register-handler (msg action) handler))
    (log/info (or log-label (str message-domain " wireless link handlers registered")))))
