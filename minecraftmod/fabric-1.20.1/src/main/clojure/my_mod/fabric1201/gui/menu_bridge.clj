(ns cn.li.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 menu bridge.

  Keeps AbstractContainerMenu gen-class isolated and exposes menu-bridge constructors.

  NOTE: This project uses official Mojang mappings on Fabric, so the menu
  base class is `net.minecraft.world.inventory.AbstractContainerMenu`."
  (:require [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.inventory AbstractContainerMenu MenuType Slot]))

(gen-class
  :name my_mod.fabric1201.gui.FabricMenuBridge
  :extends net.minecraft.world.inventory.AbstractContainerMenu
  :state state
  :init init
  :constructors {[int Object clojure.lang.IPersistentMap] [net.minecraft.world.inventory.MenuType]}
  :methods [[getClojureContainer [] Object]
            [tick [] void]
            [broadcastChanges [] void]
            [addSlot [net.minecraft.world.inventory.Slot] net.minecraft.world.inventory.Slot]])

(defn -init
  "Initialize ScreenHandler wrapper"
  [menu-id menu-type clj-container]
  [[menu-type] (atom clj-container)])

(defn -getClojureContainer [this]
  @(.state this))

(defn -tick [this]
  (gui/safe-tick! (-getClojureContainer this)))

(defn -stillValid [this player]
  (gui/safe-validate (-getClojureContainer this) player))

(defn -removed [this player]
  (let [clj-container (-getClojureContainer this)]
    (gui/safe-close! clj-container)
    (gui/unregister-active-container! clj-container)
    (gui/unregister-player-container! player)
    (log/info "Menu closed for player" (.getName player))))

(defn -broadcastChanges [this]
  (.broadcastChanges (.superclass (class this)) this)
  (gui/safe-sync! (-getClojureContainer this)))

(defn -addSlot [this slot]
  (.addSlot (.superclass (class this)) this slot))

(defn -quickMoveStack [this player slot-index]
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasItem slot))
        (let [stack (.getItem slot)
              clj-container (-getClojureContainer this)]
          (gui/execute-quick-move-fabric this clj-container slot-index slot stack))
        net.minecraft.world.item.ItemStack/EMPTY))
    (catch Exception e
      (log/error "Error in quickMoveStack:" (.getMessage e))
      net.minecraft.world.item.ItemStack/EMPTY)))

(defn -canTakeItemForPickAll [this stack slot] true)
(defn -canDragTo [this slot] true)

(defn create-menu-bridge
  "Create FabricMenuBridge instance without ns-load class resolution."
  [window-id menu-type clj-container]
  (clojure.lang.Reflector/invokeConstructor
    (Class/forName "my_mod.fabric1201.gui.FabricMenuBridge")
    (object-array [window-id menu-type clj-container])))

(defn create-screen-handler-bridge
  "Backward-compatible alias."
  [window-id menu-type clj-container]
  (create-menu-bridge window-id menu-type clj-container))
