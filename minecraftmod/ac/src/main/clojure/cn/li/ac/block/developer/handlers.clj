(ns cn.li.ac.block.developer.handlers
  (:require [clojure.string :as str]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.block.developer.logic :as dev-logic]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.machine.wireless-handlers :as wireless-handlers]
            [cn.li.ac.block.machine.handlers :as machine-handlers]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.entity :as entity])
  (:import [cn.li.acapi.wireless IWirelessNode]))

(defn- msg [action] (msg-registry/msg :developer action))

(defn- open-tile [payload player]
  (machine-handlers/open-container-tile payload player))

(defn- get-linked-node-for-receiver [tile]
  (when-let [conn (try (wireless-api/get-node-conn-by-receiver tile) (catch Exception _ nil))]
    (try (node-conn/get-node conn) (catch Exception _ nil))))

(defn handle-start-development [payload player]
  (let [tile (open-tile payload player)
        world (net-helpers/get-world player)]
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
  (let [tile (open-tile payload player)
        world (net-helpers/get-world player)]
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
     :extra-handlers {:start-development handle-start-development
                      :stop-development handle-stop-development}
     :log-label "Developer network handlers registered"}))
