(ns cn.li.forge1201.events
  "Forge 1.20.1 event handlers"
  (:require [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.forge1201.gui.registry-impl :as gui-registry-impl]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]
       [net.minecraft.world InteractionHand InteractionResult]
         [net.minecraft.world.level Level]
           [net.minecraftforge.event.level LevelEvent$Load LevelEvent$Unload
            BlockEvent$EntityPlaceEvent BlockEvent$BreakEvent]))

  (defn- gui-open-result?
    [ret]
    (and (map? ret)
      (contains? ret :gui-id)
      (contains? ret :player)
      (contains? ret :world)
      (contains? ret :pos)))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z block]} event-data
        block-name (str block)
        ;; Identify block ID from Minecraft block name
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "[RIGHT-CLICK] Event at (" x "," y "," z ") block:" block-name)
    (log/info "[RIGHT-CLICK] Identified block-id:" block-id)
    
    ;; Check if this block has a registered right-click handler
    (if block-id
      (if (event-metadata/has-event-handler? block-id :on-right-click)
        (do
          (log/info "[RIGHT-CLICK] Block has registered handler, dispatching to dispatcher...")
          (let [ret (dispatcher/on-block-right-click (assoc event-data :block-id block-id))]
            (log/info "[RIGHT-CLICK] Dispatcher returned:" ret)
            (when (and (map? ret) (contains? ret :gui-id) (contains? ret :player) (contains? ret :world) (contains? ret :pos))
              (try
                (let [{:keys [gui-id player world pos]} ret
                      tile-entity (.getBlockEntity ^Level world pos)]
                  (log/info "[RIGHT-CLICK] GUI result received: gui-id=" gui-id "pos=" pos "tile-entity=" (if tile-entity "present" "nil"))
                  (when (and tile-entity (not (.isClientSide ^Level world)))
                    (log/info "[RIGHT-CLICK] Opening GUI on server side...")
                    (gui-registry-impl/open-gui-for-player player gui-id tile-entity)))
                (catch Exception e
                  (log/error "[RIGHT-CLICK] Failed to open GUI:" (.getMessage e))
                  (log/error "[RIGHT-CLICK] Exception:" e))))
            ret))
        (log/info "[RIGHT-CLICK] Block has no registered :on-right-click handler"))
      (log/info "[RIGHT-CLICK] Could not identify block-id from:" block-name))))

(defn handle-right-click-event
  "Handle right-click block event directly from Forge event object"
  [^PlayerInteractEvent$RightClickBlock evt]
  (try
    (let [pos (.getPos evt)
          ^Level level (.getLevel evt)
          player (.getEntity evt)
          hand (.getHand evt)]
      ;; Forge fires this on both sides and both hands; only open GUI once on server main hand.
      (if (or (.isClientSide level) (not= hand InteractionHand/MAIN_HAND))
        (log/debug "[FORGE-RIGHT-CLICK-EVENT] Ignored: client-side or off-hand event")
        (let [block-state (.getBlockState level pos)
              ret (handle-right-click
                    {:x (.getX pos)
                     :y (.getY pos)
                     :z (.getZ pos)
                     :pos pos
                     :sneaking (.isShiftKeyDown player)
                     :player player
                     :world level
                     :block (.getBlock block-state)})]
          (log/info "[FORGE-RIGHT-CLICK-EVENT] Received Forge right-click event:" evt)
          (log/info "[FORGE-RIGHT-CLICK-EVENT] Extracted components: pos=" pos "player=" (.getGameProfile player) "block=" (.getBlock block-state))
          ;; GUI was opened: consume this interaction so vanilla item use
          ;; does not place the held block as a follow-up action.
          (when (gui-open-result? ret)
            (.setCancellationResult evt InteractionResult/CONSUME)
            (.setCanceled evt true)))))
    (catch Throwable t
      (log/error "[FORGE-RIGHT-CLICK-EVENT] EXCEPTION:" (ex-message t))
      (log/error "[FORGE-RIGHT-CLICK-EVENT] Stack trace:" t))))

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
      (dispatcher/on-block-place (assoc event-data :block-id block-id)))))

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
        (let [ret (dispatcher/on-block-break
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
