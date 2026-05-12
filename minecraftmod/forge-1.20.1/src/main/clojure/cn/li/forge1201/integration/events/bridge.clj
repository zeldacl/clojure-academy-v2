(ns cn.li.forge1201.integration.events.bridge
  "Shared helper utilities for Forge event handlers."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime])
  (:import [net.minecraft.world InteractionResult]
           [net.minecraftforge.event.entity.player PlayerInteractEvent PlayerInteractEvent$RightClickBlock]
           [net.minecraftforge.eventbus.api Event$Result]))

(defn runtime-activated?
  [player-uuid]
  (boolean (get-in (power-runtime/get-player-state player-uuid)
                   [:resource-data :activated])))

(defn deny-use!
  [evt]
  (when (instance? PlayerInteractEvent$RightClickBlock evt)
    (let [^PlayerInteractEvent$RightClickBlock right-click-evt evt]
      (.setUseItem right-click-evt Event$Result/DENY)
      (.setUseBlock right-click-evt Event$Result/DENY)))
  evt)

(defn cancel-fail!
  [^PlayerInteractEvent evt]
  (.setCancellationResult evt InteractionResult/FAIL)
  (.setCanceled evt true)
  evt)

(defn cancel-consume!
  [^PlayerInteractEvent evt]
  (.setCancellationResult evt InteractionResult/CONSUME)
  (.setCanceled evt true)
  evt)
