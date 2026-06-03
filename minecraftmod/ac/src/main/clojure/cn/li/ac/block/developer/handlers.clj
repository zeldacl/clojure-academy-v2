(ns cn.li.ac.block.developer.handlers
  (:require [clojure.string :as str]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.block.developer.logic :as dev-logic]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.machine.wireless-handlers :as wireless-handlers]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers])
  (:import [cn.li.acapi.wireless IWirelessNode]))

(defn- msg [action] (msg-registry/msg :developer action))

(defn- get-linked-node-for-receiver [tile]
  (when-let [conn (try (wireless-api/get-node-conn-by-receiver tile) (catch Exception _ nil))]
    (try (node-conn/get-node conn) (catch Exception _ nil))))

(defn handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (machine-runtime/state-or-default tile dev-logic/dev-default-state)
            linked-node (get-linked-node-for-receiver tile)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state 50000.0)
         :tier (:tier state "normal")
         :user-uuid (:user-uuid state "")
         :user-name (:user-name state "")
         :development-progress (:development-progress state 0.0)
         :is-developing (:is-developing state false)
         :structure-valid (:structure-valid state false)
         :linked (some-> linked-node wireless-handlers/wireless-node->info)
         :avail []})
      {:energy 0.0 :max-energy 0.0 :tier "normal" :user-uuid "" :user-name ""
       :development-progress 0.0 :is-developing false :structure-valid false
       :linked nil :avail []})))

(defn handle-start-development [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if-not tile
      {:success false :reason "no-tile"}
      (let [state (machine-runtime/state-or-default tile dev-logic/dev-default-state)
            pid (uuid/player-uuid player)
            holder (str (:user-uuid state ""))]
        (cond
          (not (:structure-valid state false)) {:success false :reason "invalid-structure"}
          (and (not (str/blank? holder)) (not= holder pid)) {:success false :reason "wrong-user"}
          :else
          (do
            (machine-runtime/commit-state! tile world nil state
                                           (assoc state :is-developing true
                                                  :user-uuid pid
                                                  :user-name (entity/player-get-name player)))
            {:success true}))))))

(defn handle-stop-development [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (machine-runtime/state-or-default tile dev-logic/dev-default-state)
            state' (assoc state :is-developing false)]
        (machine-runtime/commit-state! tile world nil state state')
        {:success true})
      {:success false})))

(defn register-network-handlers! []
  (wireless-handlers/register-link-handlers!
    {:message-domain :developer
     :get-linked-node get-linked-node-for-receiver
     :link! wireless-api/link-receiver-to-node!
     :unlink! wireless-api/unlink-receiver-from-node!
     :status-fn handle-get-status
     :extra-handlers {:start-development handle-start-development
                      :stop-development handle-stop-development}
     :log-label "Developer network handlers registered"}))
