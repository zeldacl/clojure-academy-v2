(ns cn.li.mc1201.integration.event-handlers
  "Shared event handler logic for block interactions.
  
  Platform-specific dispatchers and handlers are passed as function parameters
  to enable both Forge and Fabric to use identical core event processing logic."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.block.query :as bquery]
            [cn.li.mc1201.integration.event-feedback :as event-feedback]
            [cn.li.mc1201.integration.event-helpers-core :as event-helpers])
  (:import [net.minecraft.world.entity.player Player]))

(defn runtime-active-result
  [event-kind]
  (cond-> {:event-result :runtime-active
           :event-kind event-kind
           :cancel? true}
    (= event-kind :block-place) (assoc :cancel-place? true)
    (= event-kind :block-break) (assoc :cancel-break? true)))

(defn runtime-active-result?
  [ret]
  (= :runtime-active (:event-result ret)))

(defn- runtime-active-event?
  [event-data]
  (boolean
    (when-let [player (:player event-data)]
      (when (instance? Player player)
        (try
          (event-helpers/runtime-activated? player)
          (catch Throwable t
            (log/debug "Runtime active check skipped:" (.getMessage t))
            false))))))

(defn- identify-block-id
  [block]
  (bquery/identify-block-from-full-name (str block)))

(defn- dispatch-block-event
  [event-data dispatcher-fn event-key log-prefix]
  (let [{:keys [x y z block]} event-data
        block-name (str block)
        block-id (identify-block-id block)]
    (log/info (str log-prefix " Event at (" x "," y "," z ") block:" block-name))
    (log/info (str log-prefix " Identified block-id:" block-id))
    (when block-id
      (dispatcher-fn (assoc event-data :block-id block-id :event-key event-key)))))

(defn handle-block-left-click
  "Shared left-click policy. Currently runtime-active players cancel left click."
  [event-data]
  (when (runtime-active-event? event-data)
    (runtime-active-result :left-click)))

(defn handle-block-place
  "Shared block place handler with runtime-active policy."
  [event-data dispatcher-fn log-prefix]
  (if (runtime-active-event? event-data)
    (runtime-active-result :block-place)
    (dispatch-block-event event-data dispatcher-fn :on-place log-prefix)))

(defn handle-block-break
  "Shared block break handler with runtime-active policy."
  [event-data dispatcher-fn log-prefix]
  (if (runtime-active-event? event-data)
    (runtime-active-result :block-break)
    (dispatch-block-event event-data dispatcher-fn :on-break log-prefix)))

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
  (if (runtime-active-event? event-data)
    (runtime-active-result :right-click)
    (let [{:keys [x y z block]} event-data
          block-name (str block)
          block-id (identify-block-id block)]
      (log/info (str log-prefix " Event at (" x "," y "," z ") block:" block-name))
      (log/info (str log-prefix " Identified block-id:" block-id))

      (if block-id
        (if (or (bquery/has-block-event-handler? block-id :on-right-click)
                (bquery/is-part-block? block-id))
          (do
            (log/info (str log-prefix " Block has registered handler (or is part block), dispatching..."))
            (let [ret (dispatcher-fn (assoc event-data :block-id block-id))]
              (log/info (str log-prefix " Dispatcher returned gui-id=" (:gui-id ret)
                             " player=" (some-> (:player ret) (str))
                             " pos=" (:pos ret)))
              (event-feedback/emit-feedback! event-data ret)

              ;; Handle GUI opening if result indicates it
              (when (and gui-result-pred gui-opener-fn ret (gui-result-pred ret))
                (try
                  (let [{:keys [gui-id player world pos]} ret]
                    (when (and gui-id player world pos)
                      (let [^net.minecraft.world.level.Level world world
                            ^net.minecraft.core.BlockPos pos pos
                            tile-entity (.getBlockEntity world pos)]
                        (when tile-entity
                          (log/info (str log-prefix " GUI result received: gui-id=" gui-id))
                          (gui-opener-fn gui-id player world pos tile-entity)))))
                  (catch Exception e
                    (log/error (str log-prefix " Failed to open GUI:" (.getMessage e))))))
              ret))
          (log/info (str log-prefix " Block has no registered :on-right-click handler")))
        (log/info (str log-prefix " Could not identify block-id from:" block-name))))))
