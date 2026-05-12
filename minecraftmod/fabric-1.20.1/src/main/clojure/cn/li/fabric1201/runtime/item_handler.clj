(ns cn.li.fabric1201.runtime.item-handler
  "Item use event handler for runtime-driven items (Fabric layer)."
  (:require [cn.li.mc1201.runtime.item-handler-adapter :as adapter]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.event.player UseItemCallback]
           [net.minecraft.world InteractionResultHolder InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]))

(defonce ^:private installed? (atom false))

(defn- on-item-use
  "Handle Fabric UseItemCallback event."
  [^Player player world ^InteractionHand hand]
  (let [stack (.getItemInHand player hand)
        side (if (.isClientSide world) :client :server)
        {:keys [consume?]} (adapter/handle-item-use player hand stack side {} "Fabric")]
    (if consume?
      (InteractionResultHolder/success stack)
      (InteractionResultHolder/pass ItemStack/EMPTY))))

(defn init!
  "Initialize Fabric runtime item use event handler."
  []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric runtime item handler already initialized, skipping")
    (do
      (.register UseItemCallback/EVENT
                 (reify UseItemCallback
                   (interact [_ player world hand]
                     (on-item-use player world hand))))
      (log/info "Fabric runtime item handler initialized"))))
