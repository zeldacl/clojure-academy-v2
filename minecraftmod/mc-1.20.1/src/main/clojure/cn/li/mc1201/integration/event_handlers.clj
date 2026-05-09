(ns cn.li.mc1201.integration.event-handlers
  "Shared event handler logic for block interactions.
  
  Platform-specific dispatchers and handlers are passed as function parameters
  to enable both Forge and Fabric to use identical core event processing logic."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mc1201.integration.event-feedback :as event-feedback]))

(defn handle-block-right-click
  "Shared right-click handler logic accepting platform-specific dispatcher.
  
  Args:
  - event-data: map with :x, :y, :z, :block keys (and other event info)
  - dispatcher-fn: function to call with (assoc event-data :block-id block-id) on dispatch
  - gui-result-pred: predicate to check if result is a GUI open result (can be nil for no check)
  - gui-opener-fn: function to call with (gui-id player world pos tile-entity) to open GUI (can be nil)
  - log-prefix: string prefix for logging (e.g. \"[RIGHT-CLICK]\" or empty string)
  
  Returns: result from dispatcher-fn, or nil if no handler"
  [event-data dispatcher-fn gui-result-pred gui-opener-fn log-prefix]
  (let [{:keys [x y z block]} event-data
        block-name (str block)
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info (str log-prefix " Event at (" x "," y "," z ") block:" block-name))
    (log/info (str log-prefix " Identified block-id:" block-id))
    
    (if block-id
      (if (event-metadata/has-event-handler? block-id :on-right-click)
        (do
          (log/info (str log-prefix " Block has registered handler, dispatching..."))
          (let [ret (dispatcher-fn (assoc event-data :block-id block-id))]
            (log/info (str log-prefix " Dispatcher returned:" ret))
            (event-feedback/emit-feedback! event-data ret)
            
            ;; Handle GUI opening if result indicates it
            (when (and gui-result-pred gui-opener-fn ret (gui-result-pred ret))
              (try
                (let [{:keys [gui-id player world pos]} ret]
                  (when (and gui-id player world pos)
                    (let [tile-entity (.getBlockEntity world pos)]
                      (when tile-entity
                        (log/info (str log-prefix " GUI result received: gui-id=" gui-id))
                        (gui-opener-fn gui-id player world pos tile-entity)))))
                (catch Exception e
                  (log/error (str log-prefix " Failed to open GUI:" (.getMessage e))))))
            ret))
        (log/info (str log-prefix " Block has no registered :on-right-click handler")))
      (log/info (str log-prefix " Could not identify block-id from:" block-name)))))
