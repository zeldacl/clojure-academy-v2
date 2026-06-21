(ns cn.li.forge1201.integration.events.event-apply
  "Centralized Forge event mutation helpers to keep write-back semantics consistent."
  (:import [net.minecraft.world InteractionResult]
           [net.minecraftforge.event.entity.player PlayerInteractEvent PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.eventbus.api Event Event$Result]))

(defn deny-right-click-use!
  [evt]
  (when (instance? PlayerInteractEvent$RightClickBlock evt)
    (let [^PlayerInteractEvent$RightClickBlock right-click-evt evt]
      (.setUseItem right-click-evt Event$Result/DENY)
      (.setUseBlock right-click-evt Event$Result/DENY)))
  evt)

(defn cancel-player-interact-fail!
  [^PlayerInteractEvent evt]
  (.setCancellationResult evt InteractionResult/FAIL)
  (.setCanceled evt true)
  evt)

(defn cancel-player-interact-consume!
  [^PlayerInteractEvent evt]
  (.setCancellationResult evt InteractionResult/CONSUME)
  (.setCanceled evt true)
  evt)

(defn cancel-event!
  [^Event evt]
  (.setCanceled evt true)
  evt)

(defn apply-consumed-right-click!
  "Deny both item-use and block-use so Forge does not place an item or interact
  with the block after the handler has already consumed the event.
  On the server, additionally cancel the event with InteractionResult/CONSUME
  to prevent the default processing path."
  [^PlayerInteractEvent evt client-side?]
  (deny-right-click-use! evt)
  (when-not client-side?
    (cancel-player-interact-consume! evt))
  evt)
