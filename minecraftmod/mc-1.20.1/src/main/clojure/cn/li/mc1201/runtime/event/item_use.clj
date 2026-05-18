(ns cn.li.mc1201.runtime.event.item-use
  "Shared item-use event semantics for loader platform adapters.

  Platform layers should only unpack their event/callback objects and translate
  the returned map into loader-specific results."
  (:require [cn.li.mc1201.runtime.event.safe-handler :as safe]
            [cn.li.mc1201.runtime.item-handler-core :as core])
  (:import [net.minecraft.world InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]))

(defn- ignored-result
  [^Player player]
  {:consume? false
   :item-id nil
   :player-uuid (some-> player .getUUID str)
   :plan nil})

(defn handle-finish-using!
  [entity ^ItemStack stack side label]
  (safe/invoke
   (str label " item finish-using event")
   nil
   (fn []
     (when (instance? Player entity)
       (core/dispatch-dsl-item-finish-using! ^Player entity stack side)))))

(defn handle-use
  "Returns the shared item-use result map, or a default non-consuming result
  when the event should be ignored or a handler fails."
  [^Player player hand ^ItemStack stack side opts label]
  (safe/invoke
   (str label " item use event")
   (ignored-result player)
   (fn []
     (if (= hand InteractionHand/MAIN_HAND)
       (core/process-item-use! player hand stack side opts)
       (ignored-result player)))))