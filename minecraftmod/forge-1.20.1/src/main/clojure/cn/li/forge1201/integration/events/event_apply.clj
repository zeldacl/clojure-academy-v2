(ns cn.li.forge1201.integration.events.event-apply
  "Centralized Forge event mutation helpers to keep write-back semantics consistent."
  (:import [net.minecraft.world InteractionResult]
           [net.minecraftforge.event.entity.player PlayerInteractEvent PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.eventbus.api Event$Result]))

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
  [evt]
  (.setCanceled evt true)
  evt)

(defn apply-runtime-activated-right-click!
  [^PlayerInteractEvent evt server-side?]
  (deny-right-click-use! evt)
  (when server-side?
    (cancel-player-interact-fail! evt))
  evt)

(defn apply-runtime-activated-left-click!
  [evt]
  (deny-right-click-use! evt)
  (cancel-event! evt)
  evt)

(defn apply-consumed-right-click!
  [^PlayerInteractEvent evt client-side?]
  (if client-side?
    (deny-right-click-use! evt)
    (cancel-player-interact-consume! evt))
  evt)
