(ns cn.li.ac.block.machine.wireless-handlers
  "Shared wireless generator/receiver network handlers for block GUIs."
  (:require [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.gui.container.sync-routing :as sync-routing]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.wireless.feedback :as feedback])
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

(defn- tile-from-payload
  [payload player]
  (let [container (sync-routing/require-open-container! payload player)]
    (:tile-entity container)))

(defn register-link-handlers!
  "Register list/connect/disconnect handlers for a wireless-linked machine.

  spec keys:
  - :message-domain keyword (:generator or :developer)
  - :get-linked-node (fn [tile] -> node or nil)
  - :link! (fn [tile node password need-auth?] -> bool)
  - :unlink! (fn [tile] -> nil)
  - :extra-handlers optional map msg-keyword -> handler
  - :log-label optional string"
  [{:keys [message-domain get-linked-node link! unlink! extra-handlers log-label]}]
  (let [msg (fn [action] (msg-registry/msg message-domain action))
        handle-list-nodes
        (fn [payload player]
          (try
            (let [tile (tile-from-payload payload player)
                  world (net-helpers/get-world player)]
              (log/info "[handle-list-nodes]" log-label "tile=" (some? tile))
              (if tile
                (let [tile-pos (pos/position-get-block-pos tile)
                      linked-node (get-linked-node tile)
                      avail (available-nodes world tile-pos linked-node)]
                  {:success true
                   :linked (wireless-node->info linked-node)
                   :avail avail})
                {:success false :linked nil :avail []}))
            (catch Exception e
              {:success false :error (ex-message e)})))
        handle-connect
        (fn [payload player]
          (try
            (let [world (net-helpers/get-world player)
                  device (tile-from-payload payload player)
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
                  (let [result (link! device node pass need-auth?)]
                    (assoc result :messages (feedback/result->messages message-domain result)))
                  {:success false :messages (feedback/result->messages message-domain {:success false :reason :not-found})})
                {:success false :messages (feedback/result->messages message-domain {:success false :reason :aborted})}))
            (catch Exception e
              {:success false :error (ex-message e) :messages (feedback/result->messages message-domain {:success false :reason :aborted})})))
        handle-disconnect
        (fn [payload player]
          (try
            (let [device (tile-from-payload payload player)]
              (if device
                (do (unlink! device) {:success true :messages (feedback/result->messages message-domain {:success true})})
                {:success false :messages (feedback/result->messages message-domain {:success false :reason :aborted})}))
            (catch Exception e
              {:success false :error (ex-message e) :messages (feedback/result->messages message-domain {:success false :reason :aborted})})))]
    (net-server/register-handler (msg :list-nodes) handle-list-nodes)
    (net-server/register-handler (msg :connect) handle-connect)
    (net-server/register-handler (msg :disconnect) handle-disconnect)
    (doseq [[action handler] (or extra-handlers {})]
      (net-server/register-handler (msg action) handler))))
