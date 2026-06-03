(ns cn.li.ac.block.wireless-matrix.sync-broadcast
  "Server-side GUI state broadcast for wireless matrix (no client GUI deps)."
  (:require [cn.li.ac.wireless.gui.sync.helpers :as sync-helpers]))

(defn broadcast-matrix-state!
  [world pos sync-data]
  (sync-helpers/broadcast-state world pos sync-data "matrix"))
