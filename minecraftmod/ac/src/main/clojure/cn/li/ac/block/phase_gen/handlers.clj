(ns cn.li.ac.block.phase-gen.handlers
  "Phase Generator network handlers."
  (:require [cn.li.ac.block.machine.handlers :as machine-handlers]
            [cn.li.ac.block.phase-gen.logic :as phase-logic]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]))

(defn- msg [action] (msg-registry/msg :phase-gen action))

(defn- handle-get-status [payload player]
  (machine-handlers/tile-status-response payload player phase-logic/phase-default-state
    (fn [state]
      {:energy (:energy state 0.0)
       :max-energy (:max-energy state 0.0)
       :gen-speed (:gen-speed state 0.0)
       :liquid-amount (:liquid-amount state 0)
       :tank-size (:tank-size state 0)
       :status (:status state "IDLE")})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (log/info "Phase Generator network handlers registered"))
