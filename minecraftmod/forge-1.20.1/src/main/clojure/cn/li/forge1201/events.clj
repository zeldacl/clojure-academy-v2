(ns cn.li.forge1201.events
  "Forge 1.20.1 event handlers"
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.events.metadata :as event-metadata]
            [my-mod.forge1201.gui.registry-impl :as gui-registry-impl]
            [my-mod.events.world-lifecycle :as world-lifecycle])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.event.level LevelEvent$Load LevelEvent$Unload
            BlockEvent$EntityPlaceEvent BlockEvent$BreakEvent]))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z block]} event-data
        block-name (str block)
        ;; Identify block ID from Minecraft block name
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "1.20.1 Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Check if this block has a registered right-click handler
    (when (and block-id (event-metadata/has-event-handler? block-id :on-right-click))
      (log/info "Block has registered handler, dispatching...")
      (let [ret (core/on-block-right-click (assoc event-data :block-id block-id))]
        (when (and (map? ret) (contains? ret :gui-id) (contains? ret :player) (contains? ret :world) (contains? ret :pos))
          (try
            (let [{:keys [gui-id player world pos]} ret
                  tile-entity (.getBlockEntity world pos)]
              (when (and tile-entity (not (.isClientSide world)))
                (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))
            (catch Exception e
              (log/error "Failed to open GUI from right-click handler:" (.getMessage e)))))
        ret))))

(defn handle-right-click-event
  "Handle right-click block event directly from Forge event object"
  [^PlayerInteractEvent$RightClickBlock evt]
  (try
    (let [pos (.getPos evt)
          level (.getLevel evt)
          player (.getEntity evt)
          block-state (.getBlockState level pos)]
      (handle-right-click
        {:x (.getX pos)
         :y (.getY pos)
         :z (.getZ pos)
         :pos pos
         :sneaking (.isShiftKeyDown player)
         :player player
         :world level
         :block (.getBlock block-state)}))
    (catch Throwable t
      (log/info "Error handling right-click event:" (.getMessage t))
      (.printStackTrace t))))

;; ============================================================================
;; Block Place Events (Forge 1.20.1)
;; ============================================================================

(defn handle-block-place
  "Handle block place event from event data map.
   Delegates generic checks/placement orchestration to core/mcmod."
  [event-data]
  (let [{:keys [x y z block]} event-data
        block-name (str block)
        block-id   (event-metadata/identify-block-from-full-name block-name)]
    (log/info "1.20.1 Place event at (" x "," y "," z ") block:" block-name)
    (when block-id
      (core/on-block-place (assoc event-data :block-id block-id)))))

(defn handle-block-place-event
  "Handle block place event directly from Forge event object."
  [^BlockEvent$EntityPlaceEvent evt]
  (try
    (let [pos   (.getPos evt)
          level (.getLevel evt)
          entity (.getEntity evt)
          placed-state (.getPlacedBlock evt)]
      (when (and level pos)
        (let [ret (handle-block-place
                    {:x (.getX pos)
                     :y (.getY pos)
                     :z (.getZ pos)
                     :pos pos
                     :player entity
                     :world level
                     :block (.getBlock placed-state)})]
          (when (and (map? ret) (:cancel-place? ret))
            (.setCanceled evt true)))))
    (catch Throwable t
      (log/info "Error handling block place event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-block-break-event
  "Handle block break event directly from Forge event object."
  [^BlockEvent$BreakEvent evt]
  (try
    (let [pos (.getPos evt)
          level (.getLevel evt)
          player (.getPlayer evt)
          block-state (.getBlockState level pos)
          block-id (event-metadata/identify-block-from-full-name (str (.getBlock block-state)))]
      (when block-id
        (let [ret (core/on-block-break
                    {:x (.getX pos)
                     :y (.getY pos)
                     :z (.getZ pos)
                     :pos pos
                     :player player
                     :world level
                     :block (.getBlock block-state)
                     :block-id block-id})]
          (when (and (map? ret) (:cancel-break? ret))
            (.setCanceled evt true)))))
    (catch Throwable t
      (log/info "Error handling block break event:" (.getMessage t))
      (.printStackTrace t))))

;; ============================================================================
;; World Events (Forge 1.20.1)
;; ============================================================================

(defn handle-world-load
  "Handle world load event - dispatch to registered handlers"
  [^LevelEvent$Load evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)  ; Server side only
        (log/info "World loaded, dispatching to lifecycle handlers")
        (world-lifecycle/dispatch-world-load level nil)))
    (catch Throwable t
      (log/error "Error handling world load event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-unload
  "Handle world unload event - dispatch to registered handlers"
  [^LevelEvent$Unload evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)  ; Server side only
        (log/info "World unloading, dispatching to lifecycle handlers")
        (world-lifecycle/dispatch-world-unload level)))
    (catch Throwable t
      (log/error "Error handling world unload event:" (.getMessage t))
      (.printStackTrace t))))
