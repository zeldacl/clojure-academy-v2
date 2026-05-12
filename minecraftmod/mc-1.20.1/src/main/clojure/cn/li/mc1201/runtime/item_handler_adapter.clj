(ns cn.li.mc1201.runtime.item-handler-adapter
  "Shared event-side helpers for runtime item handling.

  Platform layers remain responsible for event registration and translating the
  return value into loader-specific event results."
  (:require [cn.li.mc1201.runtime.item-handler-core :as core]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]))

(defn handle-item-finish-using!
  [entity stack side label]
  (try
    (when (instance? Player entity)
      (core/dispatch-dsl-item-finish-using! ^Player entity stack side))
    (catch Exception e
      (log/error (str "Error handling " label " item finish-using event") e))))

(defn handle-item-use
  "Returns the shared item-use result map from item-handler-core, or a default
  non-consuming result when the event should be ignored."
  [^Player player hand ^ItemStack stack side opts label]
  (try
    (if (= hand InteractionHand/MAIN_HAND)
      (core/process-item-use! player hand stack side opts)
      {:consume? false
       :item-id nil
       :player-uuid (some-> player .getUUID str)
       :plan nil})
    (catch Exception e
      (log/error (str "Error handling " label " item use event") e)
      {:consume? false
       :item-id nil
       :player-uuid (some-> player .getUUID str)
       :plan nil})))