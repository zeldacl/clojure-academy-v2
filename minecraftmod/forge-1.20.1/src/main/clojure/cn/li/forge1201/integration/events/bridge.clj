(ns cn.li.forge1201.integration.events.bridge
  "Shared helper utilities for Forge event handlers."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime])
  (:import [net.minecraft.world InteractionResult]
           [net.minecraftforge.eventbus.api Event$Result]))

(defn runtime-activated?
  [player-uuid]
  (boolean (get-in (power-runtime/get-player-state player-uuid)
                   [:resource-data :activated])))

(defn deny-use!
  [evt]
  (.setUseItem evt Event$Result/DENY)
  (.setUseBlock evt Event$Result/DENY)
  evt)

(defn cancel-fail!
  [evt]
  (.setCancellationResult evt InteractionResult/FAIL)
  (.setCanceled evt true)
  evt)

(defn cancel-consume!
  [evt]
  (.setCancellationResult evt InteractionResult/CONSUME)
  (.setCanceled evt true)
  evt)
