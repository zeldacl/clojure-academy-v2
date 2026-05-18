(ns cn.li.mc1201.gui.registry.open
  "Shared helper functions for GUI open flows in platform registry adapters."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.entity.player Player]))

(defn resolve-optional-block-pos
  "Resolve BlockPos from tile entity when available, otherwise nil.
  Supports map tile metadata and native block entities."
  [tile-entity]
  (when tile-entity
    (try
      (if (map? tile-entity)
        (:pos tile-entity)
        (clojure.lang.Reflector/invokeInstanceMethod tile-entity "getBlockPos" (object-array [])))
      (catch Exception _
        nil))))

(defn log-open-start!
  [prefix player gui-id tile-entity]
  (let [^Player player player]
    (log/info prefix "Starting GUI open: gui-id=" gui-id "player=" (.getName player) "has-tile-entity=" (not (nil? tile-entity)))))

(defn log-open-success!
  [prefix]
  (log/info prefix "GUI opened successfully"))

(defn log-open-error!
  [prefix e]
  (log/error prefix "Failed to open GUI:" (.getMessage ^Throwable e))
  (log/error prefix "Exception:" e))

(defn open-player-menu-with-fallback!
  "Try openHandledScreen first, then openMenu as fallback for cross-loader compatibility."
  [player factory]
  (try
    (clojure.lang.Reflector/invokeInstanceMethod player "openHandledScreen" (object-array [factory]))
    (catch Exception _
      (clojure.lang.Reflector/invokeInstanceMethod player "openMenu" (object-array [factory])))))
