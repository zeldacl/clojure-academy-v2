(ns cn.li.fabric1201.gui.block-sync-broadcast
  "Fabric server broadcast for block GUI state (players tracking block pos)."
  (:require [cn.li.fabric1201.gui.network.shared :as shared]
            [cn.li.mcmod.gui.sync-api :as gui-sync-api]
            [cn.li.mc1201.gui.network.block-sync :as block-sync]
            [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.networking.v1 PlayerLookup
            ServerPlayNetworking]
           [net.minecraft.core BlockPos]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.server.level ServerPlayer]))

(defn- block-pos
  [pos]
  (cond
    (instance? BlockPos pos) pos
    (and (number? (:pos-x pos)) (number? (:pos-y pos)) (number? (:pos-z pos)))
    (BlockPos. (int (:pos-x pos)) (int (:pos-y pos)) (int (:pos-z pos)))
    :else nil))

(defn broadcast-gui-state!
  [world pos sync-data]
  (when world
    (try
      (let [^ServerLevel level world
            ^BlockPos block-pos (block-pos pos)]
        (when block-pos
          (let [buf (shared/make-buf (packet-base/push-map block-sync/BLOCK-GUI-STATE-MSG-ID sync-data))]
            (doseq [^ServerPlayer player (PlayerLookup/tracking level block-pos)]
              (ServerPlayNetworking/send player shared/s2c-channel buf)))))
      (catch Exception e
        (log/debug "Fabric GUI block sync broadcast skipped:" (ex-message e)))))
  nil)

(defmethod gui-sync-api/broadcast-gui-state!* :fabric-1.20.1
  [world pos sync-data]
  (broadcast-gui-state! world pos sync-data))
