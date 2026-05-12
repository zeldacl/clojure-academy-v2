(ns cn.li.forge1201.integration.events.bridge
  "Shared helper utilities for Forge event handlers."
  (:require [cn.li.mcmod.runtime.hooks-core :as power-runtime]))

(defn runtime-activated?
  [player-uuid]
  (boolean (get-in (power-runtime/get-player-state player-uuid)
                   [:resource-data :activated])))
