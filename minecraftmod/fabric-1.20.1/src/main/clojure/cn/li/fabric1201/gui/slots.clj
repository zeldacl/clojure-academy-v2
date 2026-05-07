(ns cn.li.fabric1201.gui.slots
  "Fabric 1.20.1 GUI slot placeholder helpers.

  The previous draft used Yarn-era imports and multiple `gen-class` forms in one
  namespace. Keep a lightweight placeholder until the Fabric menu bridge is
  rebuilt around Mojmap-compatible runtime proxies." 
  (:require [cn.li.mcmod.util.log :as log]))

(defn create-energy-slot [& _args] nil)
(defn create-plate-slot [& _args] nil)
(defn create-core-slot [& _args] nil)
(defn create-output-slot [& _args] nil)
(defn create-standard-slot [& _args] nil)

(defn add-gui-slots [& _args]
  (log/debug "Fabric GUI slots are not wired yet"))

(defn get-gui-slot-ranges [& _args]
  nil)

(defn add-player-inventory-slots [& _args]
  (log/debug "Fabric player inventory slots are not wired yet"))

(defn get-slot-range [& _args]
  nil)

(defn slot-in-range? [& _args]
  false)

(defn log-slot-contents [& _args]
  (log/debug "Fabric slot logging is not wired yet"))

(defn validate-slot-setup [& _args]
  (log/warn "Fabric slot validation is not implemented yet")
  false)
