(ns cn.li.ac.block.imag-fusor.handlers
  "Imaginary Fusor network handlers."
  (:require [cn.li.ac.block.imag-fusor.logic :as fusor-logic]
            [cn.li.ac.block.machine.handlers :as machine-handlers]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.util.log :as log]))

(defn- handle-get-status [payload player]
  (machine-handlers/tile-status-response payload player fusor-logic/fusor-default-state
    (fn [state]
      {:energy (:energy state 0.0)
       :max-energy (:max-energy state 0.0)
       :crafting-progress (:crafting-progress state 0)
       :work-progress (:work-progress state 0.0)
       :max-progress (:max-progress state 0)
       :current-recipe-liquid (:current-recipe-liquid state 0)
       :liquid-amount (:liquid-amount state 0)
       :tank-size (:tank-size state 0)
       :working (:working state false)})))

(defn register-network-handlers! []
  (net-server/register-handler (msg-registry/msg :imag-fusor :get-status) handle-get-status)
  (log/info "Imaginary Fusor network handlers registered"))
