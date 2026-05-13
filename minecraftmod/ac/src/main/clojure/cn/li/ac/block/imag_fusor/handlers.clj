(ns cn.li.ac.block.imag-fusor.handlers
  "Imaginary Fusor network handlers."
  (:require [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.imag-fusor.logic :as fusor-logic]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log]))

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) fusor-logic/fusor-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state 0.0)
         :crafting-progress (:crafting-progress state 0)
         :work-progress (:work-progress state 0.0)
         :max-progress (:max-progress state 0)
         :current-recipe-liquid (:current-recipe-liquid state 0)
         :liquid-amount (:liquid-amount state 0)
         :tank-size (:tank-size state 0)
         :working (:working state false)})
      {:energy 0.0 :max-energy 0.0 :crafting-progress 0 :work-progress 0.0
       :max-progress 0 :current-recipe-liquid 0
       :liquid-amount 0 :tank-size 0 :working false})))

(defn register-network-handlers! []
  (let [msg-fn (requiring-resolve 'cn.li.ac.wireless.gui.message.registry/msg)]
    (net-server/register-handler (msg-fn :imag-fusor :get-status) handle-get-status)
    (log/info "Imaginary Fusor network handlers registered")))
