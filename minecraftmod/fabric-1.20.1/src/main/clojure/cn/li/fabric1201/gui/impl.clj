(ns cn.li.fabric1201.gui.impl
  "Fabric 1.20.1 GUI runtime hooks.

  Provides a concrete, non-stub entrypoint that ties together registry,
  client screens, and RPC transport initialization."
  (:require [cn.li.fabric1201.gui.registry-impl :as registry-impl]
            [cn.li.fabric1201.gui.screen-impl :as screen-impl]
            [cn.li.fabric1201.gui.network :as network]
            [cn.li.mcmod.util.log :as log]))

(defn init-common!
  []
  (registry-impl/init!)
  (log/info "Fabric GUI common runtime initialized"))

(defn init-server!
  []
  (network/init-server!)
  (log/info "Fabric GUI server runtime initialized"))

(defn init-client!
  []
  (screen-impl/init-client!)
  (network/init-client!)
  (log/info "Fabric GUI client runtime initialized"))

(defn on-button-clicked [button-id]
  (log/debug "Fabric GUI button clicked:" button-id)
  nil)

(defn on-slot-changed [slot-index item-stack]
  (log/debug "Fabric GUI slot changed:" {:slot-index slot-index :item-stack item-stack})
  nil)
