(ns my-mod.forge1201.events
  "Forge 1.20.1 event handlers"
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.events.metadata :as event-metadata]
            [my-mod.block.dsl :as bdsl]
            [my-mod.platform.world :as pworld]
            [my-mod.forge1201.gui.registry-impl :as gui-registry-impl]
            [my-mod.wireless.world-data :as wd])
  (:import [net.minecraftforge.event.entity.player PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.event.level LevelEvent$Load LevelEvent$Unload BlockEvent$EntityPlaceEvent]))

(defn handle-right-click
  "Handle right-click block event from event data map"
  [event-data]
  (let [{:keys [x y z player world block]} event-data
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
   Performs generic multi-block overlap checks, then dispatches :on-place."
  [event-data]
  (let [{:keys [x y z world pos block]} event-data
        block-name (str block)
        block-id   (event-metadata/identify-block-from-full-name block-name)]
    (log/info "1.20.1 Place event at (" x "," y "," z ") block:" block-name)
    (when block-id
      (let [block-spec (bdsl/get-block block-id)
            ;; 使用 multi-block 体积做放置检查。
            ;; 对结构占用的每一个坐标：
            ;; - 对于 origin 位置（pos 本身），不做额外限制，交给 vanilla / Forge 的 mayPlace 处理。
            ;; - 对于其余所有坐标，当前方块必须是“可替换”的（这里保守地认为只有空气可替换）。
            ;; 同时打印详细日志，方便调试每个坐标的判定结果。
            cancel? (when (:multi-block? block-spec)
                      (let [positions (bdsl/all-multi-block-positions pos block-spec)]
                        (some (fn [bp]
                                (let [state (pworld/world-get-block-state world bp)
                                      blk   (when state (.getBlock state))
                                      replaceable? (or (nil? state)
                                                       (.isAir state))]
                                  (log/info "Matrix place check at" bp
                                            "state=" (str state)
                                            "block=" (when blk (str blk))
                                            "replaceable?=" replaceable?)
                                  (and (not (= bp pos)) ; 跳过 origin，自身由 Forge 放置规则控制
                                       (not replaceable?))))
                              positions)))]
        (if cancel?
          (do
            (log/info "Cancelling place for multi-block" block-id "due to occupied space")
            {:cancel-place? true})
          (when (event-metadata/has-event-handler? block-id :on-place)
            (log/info "Block has :on-place handler, dispatching...")
            (core/on-block-place (assoc event-data :block-id block-id))))))))

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

;; ============================================================================
;; World Events (Forge 1.20.1)
;; ============================================================================

(defn handle-world-load
  "Handle world load event - restore wireless network data"
  [^LevelEvent$Load evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)  ; Server side only
        (log/info "World loaded, initializing wireless system")
        (wd/on-world-load level nil)))
    (catch Throwable t
      (log/error "Error handling world load event:" (.getMessage t))
      (.printStackTrace t))))

(defn handle-world-unload
  "Handle world unload event - cleanup wireless network data"
  [^LevelEvent$Unload evt]
  (try
    (let [level (.getLevel evt)]
      (when-not (.isClientSide level)  ; Server side only
        (log/info "World unloading, cleaning up wireless system")
        (wd/on-world-unload level)))
    (catch Throwable t
      (log/error "Error handling world unload event:" (.getMessage t))
      (.printStackTrace t))))
