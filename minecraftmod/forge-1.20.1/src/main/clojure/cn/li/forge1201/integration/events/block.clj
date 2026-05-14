(ns cn.li.forge1201.integration.events.block
  "Forge block place/break event handlers." 
  (:require [cn.li.mc1201.integration.event-support :as event-support]
            [cn.li.mcmod.events.dispatcher :as dispatcher]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.util.log :as log]
            [cn.li.forge1201.integration.events.event-apply :as event-apply]
             [cn.li.mcmod.runtime.hooks-core :as power-runtime])
  (:import [net.minecraft.world.entity.player Player]
           [net.minecraftforge.event.level BlockEvent$EntityPlaceEvent BlockEvent$BreakEvent]))

(defn handle-block-place
  [event-data]
  (let [{:keys [x y z block]} event-data
        block-name (str block)
        block-id (event-metadata/identify-block-from-full-name block-name)]
    (log/info "1.20.1 Place event at (" x "," y "," z ") block:" block-name)
    (when block-id
      (dispatcher/on-block-place (assoc event-data :block-id block-id)))))

(defn handle-block-place-event
  [^BlockEvent$EntityPlaceEvent evt]
  (event-support/guarded-call
    "block place event"
    nil
    (fn []
      (let [pos (.getPos evt)
            level (.getLevel evt)
            entity (.getEntity evt)
            placed-state (.getPlacedBlock evt)]
        (when (and level pos)
          (if (and entity
                   (instance? Player entity)
                   (power-runtime/runtime-activated? (str (.getUUID ^Player entity))))
            (event-apply/cancel-event! evt)
            (let [ret (handle-block-place
                        {:x (.getX pos)
                         :y (.getY pos)
                         :z (.getZ pos)
                         :pos pos
                         :player entity
                         :world level
                         :block (.getBlock placed-state)})]
              (when (and (map? ret) (:cancel-place? ret))
                (event-apply/cancel-event! evt)))))))))

(defn handle-block-break-event
  [^BlockEvent$BreakEvent evt]
  (event-support/guarded-call
    "block break event"
    nil
    (fn []
      (let [pos (.getPos evt)
            level (.getLevel evt)
            player (.getPlayer evt)
            block-state (.getBlockState level pos)
            block-id (event-metadata/identify-block-from-full-name (str (.getBlock block-state)))]
        (if (power-runtime/runtime-activated? (str (.getUUID player)))
          (event-apply/cancel-event! evt)
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
                (event-apply/cancel-event! evt)))))))))
