(ns cn.li.ac.block.phase-gen.handlers
  "Phase Generator network handlers."
  (:require [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.ac.block.phase-gen.logic :as phase-logic]
            [cn.li.mcmod.util.log :as log]))

(defn- msg [action] (msg-registry/msg :phase-gen action))

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) phase-logic/phase-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state 0.0)
         :gen-speed (:gen-speed state 0.0)
         :liquid-amount (:liquid-amount state 0)
         :tank-size (:tank-size state 0)
         :status (:status state "IDLE")})
      {:energy 0.0 :max-energy 0.0 :gen-speed 0.0 :status "ERROR"})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (log/info "Phase Generator network handlers registered"))
