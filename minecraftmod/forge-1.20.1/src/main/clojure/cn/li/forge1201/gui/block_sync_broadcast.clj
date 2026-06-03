(ns cn.li.forge1201.gui.block-sync-broadcast
  "Forge server broadcast for block GUI state (chunk-tracking players)."
  (:require [cn.li.ac.gui.platform-adapter.sync-api :as gui-sync-api]
            [cn.li.mc1201.gui.network.block-sync :as block-sync]
            [cn.li.mc1201.runtime.network-payload :as network-payload]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.network ClojureNetwork]
           [net.minecraft.core BlockPos]
           [net.minecraft.server.level ServerLevel]))

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
          (ClojureNetwork/broadcastGuiBlockStateToTrackingChunk
            level
            block-pos
            (network-payload/serialize-message block-sync/BLOCK-GUI-STATE-MSG-ID sync-data))))
      (catch Exception e
        (log/debug "Forge GUI block sync broadcast skipped:" (ex-message e)))))
  nil)

(defmethod gui-sync-api/broadcast-gui-state!* :forge-1.20.1
  [world pos sync-data]
  (broadcast-gui-state! world pos sync-data))
