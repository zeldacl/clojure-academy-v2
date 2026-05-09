(ns cn.li.mc1201.gui.menu-bridge-common
  "Shared lifecycle and quick-move helpers for platform menu bridges."
  (:require [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.gui CMenuBridge]
           [net.minecraft.world.inventory AbstractContainerMenu Slot]
           [net.minecraft.world.item ItemStack]))

(defn remove-menu!
  [this clj-container player {:keys [on-container-id
                                     call-super-removed?
                                     log-message]
                              :or {call-super-removed? false
                                   log-message "Menu closed for player"}}]
  (let [cid (gui/get-menu-container-id this)]
    (when cid
      (when on-container-id
        (on-container-id cid))
      (gui/unregister-container-by-id! cid)))
  (gui/unregister-menu-container! this)
  (gui/safe-close! clj-container)
  (gui/unregister-active-container! clj-container)
  (gui/unregister-player-container! player)
  (when call-super-removed?
    (let [^CMenuBridge s this]
      (.callSuperRemoved s player)))
  (log/info log-message (str player)))

(defn broadcast-and-sync!
  [this clj-container before-super-broadcast!]
  (when before-super-broadcast!
    (before-super-broadcast!))
  (let [^CMenuBridge s this]
    (.callSuperBroadcastChanges s))
  (gui/safe-sync! clj-container))

(defn quick-move-stack
  [this clj-container slot-index error-prefix]
  (try
    (let [^Slot slot (.getSlot ^AbstractContainerMenu this slot-index)]
      (if (and slot (.hasItem slot))
        (let [^ItemStack stack (.getItem slot)]
          (gui/execute-quick-move-forge this clj-container slot-index slot stack))
        ItemStack/EMPTY))
    (catch Exception e
      (log/error error-prefix (.getMessage e))
      ItemStack/EMPTY)))

(defn finalize-menu-registration!
  [menu window-id clj-container]
  (gui/register-menu-container! menu clj-container)
  (gui/register-container-by-id! window-id clj-container)
  menu)