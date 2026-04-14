(ns cn.li.fabric1201.events
  "Fabric 1.20.1 event handlers"
  (:require [cn.li.ac.core :as core]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.fabric1201.gui.registry-impl :as gui-registry-impl]
            [cn.li.ac.wireless.data.world :as wd])
  (:import [net.fabricmc.fabric.api.event.player UseBlockCallback]
           [net.fabricmc.fabric.api.event.player PlayerBlockBreakEvents]
           [net.minecraft.network.chat Component]
           [net.minecraft.world InteractionResult]
           [net.fabricmc.fabric.api.event.lifecycle.v1 ServerWorldEvents]))

  (defn- gui-open-result?
    [ret]
    (and (map? ret)
      (contains? ret :gui-id)
      (contains? ret :player)
      (contains? ret :world)
      (contains? ret :pos)))

(defn- consume-result? [ret]
  (and (map? ret) (true? (:consume? ret))))

(defn- feedback-component
  [{:keys [type key args text]}]
  (case type
    :translatable (Component/translatable (str key) (into-array Object (map str (or args []))))
    :literal (Component/literal (str text))
    nil))

(defn- emit-feedback!
  [event-data ret]
  (let [world (:world event-data)
        player (:player event-data)
        messages (when (map? ret) (:messages ret))]
    (when (and world player (not (.isClientSide world)) (seq messages))
      (doseq [m messages]
        (when-let [c (feedback-component m)]
          (.sendSystemMessage player c))))))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z block]} event-data
        block-name (str block)
        ;; Identify block ID from Minecraft block name
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "Fabric 1.20.1 Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Check if this block has a registered right-click handler
    (when (and block-id (event-metadata/has-event-handler? block-id :on-right-click))
      (log/info "Block has registered handler, dispatching...")
      (let [ret (core/on-block-right-click (assoc event-data :block-id block-id))]
        (emit-feedback! event-data ret)
        (when (and (map? ret) (contains? ret :gui-id) (contains? ret :player) (contains? ret :world) (contains? ret :pos))
          (try
            (let [{:keys [gui-id player world pos]} ret
                  tile-entity (.getBlockEntity world pos)]
              (when (and tile-entity (not (.isClientSide world)))
                (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))
            (catch Exception e
              (log/error "Failed to open GUI from right-click handler:" (.getMessage e)))))
        ret))))

(defn handle-use-block
  "Handle Fabric UseBlockCallback event"
  [player world hand hit-result]
  (try
    (let [pos (.getBlockPos hit-result)
        block-state (.getBlockState world pos)
        item-stack (.getStackInHand player hand)
        ret (handle-right-click
          {:x (.getX pos)
           :y (.getY pos)
           :z (.getZ pos)
           :pos pos
           :sneaking (.isShiftKeyDown player)
           :player player
           :hand hand
           :item-stack item-stack
           :world world
           :block (.getBlock block-state)})]
    ;; GUI was opened: consume this interaction so vanilla item use
    ;; does not place the held block as a follow-up action.
    (if (or (gui-open-result? ret)
        (consume-result? ret))
      InteractionResult/CONSUME
      InteractionResult/PASS))
    (catch Throwable t
      (log/info "Error handling use block event:" (.getMessage t))
      (.printStackTrace t)
      InteractionResult/PASS)))

(defn handle-block-break
  "Handle Fabric block break callback. Return true to continue vanilla break."
  [world player pos state _be]
  (try
    (let [block (.getBlock state)
          block-id (event-metadata/identify-block-from-full-name (str block))]
      (if-not block-id
        true
        (let [ret (core/on-block-break
                    {:x (.getX pos)
                     :y (.getY pos)
                     :z (.getZ pos)
                     :pos pos
                     :player player
                     :world world
                     :block block
                     :block-id block-id})]
          (not (and (map? ret) (:cancel-break? ret))))))
    (catch Throwable t
      (log/error "Error handling fabric block break:" (.getMessage t))
      true)))

(defn register-events
  "Register Fabric event listeners"
  []
  (log/info "Registering Fabric event listeners...")
  
  ;; Register block interaction event
  (.register UseBlockCallback/EVENT
    (reify UseBlockCallback
      (interact [_ player world hand hit-result]
        (handle-use-block player world hand hit-result))))

  ;; Register block break event
  (.register PlayerBlockBreakEvents/BEFORE
    (reify net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents$Before
      (beforeBlockBreak [_ world player pos state be]
        (boolean (handle-block-break world player pos state be)))))
  
  ;; Register world load event
  (.register ServerWorldEvents/LOAD
    (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Load
      (onServerWorldLoad [_ _server world]
        (try
          (log/info "Fabric world load event, initializing wireless system")
          (wd/on-world-load world nil)
          (catch Throwable t
            (log/error "Error handling fabric world load:" (.getMessage t)))))))
  
  ;; Register world save event
  (.register ServerWorldEvents/SAVE
    (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Save
      (onServerWorldSave [_ _server world]
        (try
          (log/info "Fabric world save event, preparing wireless data")
          (wd/on-world-save world)
          (catch Throwable t
            (log/error "Error handling fabric world save:" (.getMessage t)))))))
  
  ;; Register world unload event
  (.register ServerWorldEvents/UNLOAD
    (reify net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents$Unload
      (onServerWorldUnload [_ _server world]
        (try
          (log/info "Fabric world unload event, cleaning up wireless system")
          (wd/on-world-unload world)
          (catch Throwable t
            (log/error "Error handling fabric world unload:" (.getMessage t)))))))
  
  (log/info "Fabric event listeners registered"))
