(ns cn.li.fabric1201.gui.impl
  "Fabric 1.20.1 GUI placeholder hooks.

  The real cross-platform GUI API lives under `cn.li.ac.gui.platform-adapter`
  and `cn.li.mcmod.gui.metadata`. This namespace is intentionally lightweight so
  Fabric can compile even while its GUI runtime bridge is still being normalized."
  (:require [cn.li.mcmod.util.log :as log]))

(defn note-placeholder!
  [action payload]
  (log/info "Fabric 1.20.1 GUI placeholder:" action payload)
  nil)

(defn on-button-clicked [button-id]
  (note-placeholder! :button-click button-id))

(defn on-slot-changed [slot-index item-stack]
  (note-placeholder! :slot-changed {:slot-index slot-index :item-stack item-stack}))
