(ns my-mod.core
  (:require [my-mod.defs :as defs]
            [my-mod.util.log :as log]
            [my-mod.gui.api :as gui-api]
            [my-mod.gui.core :as gui-core]
            [my-mod.events.metadata :as event-metadata]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (log/info "Initializing core for mod-id=" defs/mod-id)
  ;; Initialize event metadata system
  (event-metadata/init-event-metadata!))

(defn on-block-right-click
  "Generic block right-click event handler.
  
  Dispatches to block-specific handlers registered in event metadata system.
  Platform code does not know which blocks have handlers.
  
  Args:
    ctx: Event context map with :x :y :z :player :world :block :block-id"
  [{:keys [x y z player world block block-id] :as ctx}]
  (log/info "Right-click event at (" x "," y "," z ") for block-id:" block-id)
  
  ;; Dispatch to block-specific handler if registered
  (when-let [handler (event-metadata/get-block-event-handler block-id :on-right-click)]
    (log/info "Dispatching to registered handler for block:" block-id)
    (handler ctx)))
