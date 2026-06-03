(ns cn.li.ac.block.wind-gen.handlers
  "Wind Generator network handlers."
  (:require [cn.li.ac.block.wind-gen.logic :as wind-logic]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log]))

(defn- handle-get-status-main [payload player]
  (let [w (net-helpers/get-world player)
        tile (net-helpers/get-tile-at w payload)
        state (or (and tile (platform-be/get-custom-state tile)) wind-logic/main-default-state)]
    {:complete (boolean (:complete state false))
     :no-obstacle (boolean (:no-obstacle state false))
     :fan-installed (boolean (:fan-installed state false))
     :status (str (:status state "INCOMPLETE"))}))

(defn- handle-get-status-base [payload player]
  (let [w (net-helpers/get-world player)
        tile (net-helpers/get-tile-at w payload)
        state (or (and tile (platform-be/get-custom-state tile)) wind-logic/base-default-state)]
    {:energy (double (:energy state 0.0))
     :max-energy (double (:max-energy state 0.0))
     :gen-speed (double (:gen-speed state 0.0))
     :status (str (:status state "BASE_ONLY"))
     :completeness (str (:completeness state "BASE_ONLY"))}))

(defn register-network-handlers! []
  (net-server/register-handler (msg-registry/msg :wind-gen :get-status-main) handle-get-status-main)
  (net-server/register-handler (msg-registry/msg :wind-gen :get-status-base) handle-get-status-base)
  (log/info "Wind Generator network handlers registered"))
