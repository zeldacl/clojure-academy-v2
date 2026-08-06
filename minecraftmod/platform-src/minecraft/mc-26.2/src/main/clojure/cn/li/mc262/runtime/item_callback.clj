(ns cn.li.mc262.runtime.item-callback
  "Build the single native Item.use callback shared by all loaders."
  (:require [cn.li.mcbase.runtime.event.item-use :as item-use])
  (:import [net.minecraft.world InteractionHand InteractionResult]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.level Level]))

(defn build
  "Create an IFn that dispatches the shared item-use flow.

  `with-owner` is the loader boundary for optional player-owner binding; it
  receives player, side, and a zero-argument function.

  Returns InteractionResult (26.2; InteractionResultHolder removed)."
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
          (.heldItemTransformedTo InteractionResult/SUCCESS stack)
          InteractionResult/PASS)))))
