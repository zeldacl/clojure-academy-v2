(ns cn.li.ac.block.machine.handlers
  "Shared block machine network handler helpers."
  (:require [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.mcmod.platform.be :as platform-be]))

(defn tile-status-response
  "Resolve tile at `payload` and build a status map from its custom state.

  `status-fn` receives the effective state (tile custom state or `default-state`;
  when no tile exists, `default-state` is passed)."
  [payload player default-state status-fn]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        state (if tile
                (or (try (platform-be/get-custom-state tile) (catch Exception _ nil))
                    default-state)
                default-state)]
    (status-fn state)))
