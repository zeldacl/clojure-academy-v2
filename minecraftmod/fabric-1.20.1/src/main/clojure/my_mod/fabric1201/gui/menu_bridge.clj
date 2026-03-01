(ns my-mod.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 menu bridge.

  Keeps ScreenHandler gen-class isolated and exposes menu-bridge constructors."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.util.log :as log])
  (:import [net.minecraft.screen ScreenHandler ScreenHandlerType]
           [net.minecraft.screen.slot Slot]))

(gen-class
  :name my_mod.fabric1201.gui.FabricScreenHandlerBridge
  :extends net.minecraft.screen.ScreenHandler
  :state state
  :init init
  :constructors {[int Object clojure.lang.IPersistentMap] [net.minecraft.screen.ScreenHandlerType int]}
  :methods [[getClojureContainer [] Object]
            [tick [] void]])

(defn -init
  "Initialize ScreenHandler wrapper"
  [sync-id handler-type clj-container]
  [[handler-type sync-id] (atom clj-container)])

(defn -getClojureContainer [this]
  @(.state this))

(defn -tick [this]
  (gui/safe-tick! (-getClojureContainer this)))

(defn -canUse [this player]
  (gui/safe-validate (-getClojureContainer this) player))

(defn -close [this player]
  (.close (.superclass (class this)) this player)
  (let [clj-container (-getClojureContainer this)]
    (gui/safe-close! clj-container)
    (gui/unregister-active-container! clj-container)
    (gui/unregister-player-container! player)
    (log/info "ScreenHandler closed for player" (.getName player))))

(defn -sendContentUpdates [this]
  (.sendContentUpdates (.superclass (class this)) this)
  (gui/safe-sync! (-getClojureContainer this)))

(defn -addSlot [this slot]
  (.addSlot (.superclass (class this)) this slot))

(defn -quickMove [this player slot-index]
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasStack slot))
        (let [stack (.getStack slot)
              clj-container (-getClojureContainer this)]
          (gui/execute-quick-move-fabric this clj-container slot-index slot stack))
        net.minecraft.item.ItemStack/EMPTY))
    (catch Exception e
      (log/error "Error in quickMove:" (.getMessage e))
      net.minecraft.item.ItemStack/EMPTY)))

(defn -canInsertIntoSlot [this stack slot] true)

(defn create-menu-bridge
  "Create FabricScreenHandlerBridge instance without ns-load class resolution."
  [sync-id handler-type clj-container]
  (clojure.lang.Reflector/invokeConstructor
    (Class/forName "my_mod.fabric1201.gui.FabricScreenHandlerBridge")
    (object-array [sync-id handler-type clj-container])))

(defn create-screen-handler-bridge
  "Backward-compatible alias."
  [sync-id handler-type clj-container]
  (create-menu-bridge sync-id handler-type clj-container))
