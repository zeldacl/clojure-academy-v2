(ns cn.li.ac.block.solar-gen.handlers
  (:require [cn.li.ac.block.machine.wireless-handlers :as wireless-handlers]
            [cn.li.ac.block.solar-gen.logic :as solar-logic]
            [cn.li.ac.block.solar-gen.config :as solar-config]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.mcmod.platform.be :as platform-be]))

(defn register-network-handlers! []
  (wireless-handlers/register-link-handlers!
    {:message-domain :generator
     :get-linked-node solar-logic/get-linked-node
     :link! wireless-api/link-generator-to-node!
     :unlink! wireless-api/unlink-generator-from-node!
     :status-fn (fn [_payload _player tile]
                  (let [state (or (platform-be/get-custom-state tile) {})]
                    {:energy (double (get state :energy 0.0))
                     :max-energy (double (get state :max-energy solar-config/max-energy))
                     :status (str (get state :status "STOPPED"))
                     :gen-speed (double (get state :gen-speed 0.0))}))
     :log-label "Solar Generator network handlers registered"}))
