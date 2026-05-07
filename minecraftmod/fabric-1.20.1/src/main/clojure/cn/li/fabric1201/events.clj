(ns cn.li.fabric1201.events
  "Fabric 1.20.1 event handlers"
  (:require [cn.li.ac.core :as core]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.interaction-result :as interaction-result]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.fabric1201.gui.registry-impl :as gui-registry-impl])
  (:import
           [net.minecraft.network.chat Component]
           [net.minecraft.world InteractionResult]))

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
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "Fabric 1.20.1 Right-click event at (" x "," y "," z ") block:" block-name)
    (when (and block-id (event-metadata/has-event-handler? block-id :on-right-click))
      (log/info "Block has registered handler, dispatching...")
      (let [ret (core/on-block-right-click (assoc event-data :block-id block-id))]
        (emit-feedback! event-data ret)
        (when (interaction-result/gui-open-result? ret)
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
      (if (interaction-result/interaction-consumed? ret)
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
  "Register Fabric event listeners - placeholder version"
  []
  (log/info "Registering Fabric event listeners (placeholder)...")
  (log/info "Fabric event listeners registered"))
