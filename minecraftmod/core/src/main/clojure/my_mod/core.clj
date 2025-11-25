(ns my-mod.core
  (:require [my-mod.defs :as defs]
            [my-mod.util.log :as log]
            [my-mod.gui.api :as gui-api]
            [my-mod.gui.core :as gui-core]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (log/info "Initializing core for mod-id=" defs/mod-id
            ", demo-item=" defs/demo-item-id
            ", demo-block=" defs/demo-block-id))

(defn on-block-right-click
  "Optional hook from adapters when player right-clicks a block."
  [{:keys [x y z side player world block] :as ctx}]
  (log/info "Right-click on block at (" x "," y "," z ") by player")
  
  ;; Check if it's our demo block and open GUI
  (when (gui-core/validate-gui-open player world [x y z] block)
    (log/info "Opening demo GUI for player at block position")
    (try
      (gui-api/open-gui player 1 world [x y z])
      (catch Exception e
        (log/info "Error opening GUI:" (.getMessage e))))))
