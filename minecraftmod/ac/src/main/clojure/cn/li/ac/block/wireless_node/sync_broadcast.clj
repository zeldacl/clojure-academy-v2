(ns cn.li.ac.block.wireless-node.sync-broadcast
  "Server-side GUI state broadcast for wireless node (no client GUI deps)."
  (:require [cn.li.ac.wireless.gui.sync.helpers :as sync-helpers]))

(defn broadcast-node-state!
  [world pos sync-data]
  (sync-helpers/broadcast-state world pos sync-data "node"))
