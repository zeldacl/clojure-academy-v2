(ns cn.li.mc1211.runtime.item-callback
  "Build the single native Item.use callback shared by all loaders."
  (:require [cn.li.mc1211.runtime.event.item-use :as item-use])
  (:import [net.minecraft.world InteractionResultHolder]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.level Level]
           [net.minecraft.world InteractionHand]))

(defn build
  "Create an IFn that dispatches the shared item-use flow.

  `with-owner` is the loader boundary for optional player-owner binding; it
  receives player, side, and a zero-argument function."
  [label with-owner]
  (reify clojure.lang.IFn
    (invoke [_this level player hand]
      (let [^Level level level
            ^Player player player
            ^InteractionHand hand hand
            side (if (.isClientSide level) :client :server)
            ^ItemStack stack (.getItemInHand player hand)
            {:keys [consume?]} (with-owner player side
                                 #(item-use/handle-use player hand stack side {} label))]
        (if consume?
          (InteractionResultHolder/success stack)
          (InteractionResultHolder/pass stack))))))
