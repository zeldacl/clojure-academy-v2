(ns my-mod.fabric1201.gui.provider-bridge
  "Fabric 1.20.1 provider bridge.

  Uses reify factories and delegates menu construction to menu-bridge."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.util.log :as log]
            [my-mod.fabric1201.gui.menu-bridge :as menu-bridge])
  (:import [net.minecraft.screen NamedScreenHandlerFactory]
           [net.fabricmc.fabric.api.screenhandler.v1 ExtendedScreenHandlerFactory]
           [net.minecraft.text Text]))

(defn create-menu-provider
  "Create a simple NamedScreenHandlerFactory provider."
  [gui-id tile-entity]
  (reify NamedScreenHandlerFactory
    (getDisplayName [_]
      (Text/literal (gui/get-display-name gui-id)))

    (createMenu [_ sync-id player-inventory player]
      (let [handler (gui/get-gui-handler)
            world (.getWorld player)
            pos (if tile-entity (:pos tile-entity) (.getBlockPos player))]
        (log/info "Creating menu for GUI" gui-id)
        (let [clj-container (.get-server-container handler gui-id player world pos)]
          (when-not clj-container
            (throw (ex-info "Failed to create Clojure container" {:gui-id gui-id})))
          (gui/register-active-container! clj-container)
          (gui/register-player-container! player clj-container)
          (let [handler-type (gui/get-menu-type :fabric-1.20.1 gui-id)]
            (when-not handler-type
              (throw (ex-info "ScreenHandlerType not registered" {:gui-id gui-id})))
            (menu-bridge/create-menu-bridge sync-id handler-type clj-container)))))))

(defn create-extended-menu-provider
  "Create an ExtendedScreenHandlerFactory provider with packet opening data."
  [gui-id tile-entity]
  (reify ExtendedScreenHandlerFactory
    (getDisplayName [_]
      (Text/literal (gui/get-display-name gui-id)))

    (createMenu [_ sync-id player-inventory player]
      (let [handler (gui/get-gui-handler)
            world (.getWorld player)
            pos (if tile-entity (:pos tile-entity) (.getBlockPos player))]
        (let [clj-container (.get-server-container handler gui-id player world pos)]
          (when-not clj-container
            (throw (ex-info "Failed to create Clojure container" {:gui-id gui-id})))
          (gui/register-active-container! clj-container)
          (gui/register-player-container! player clj-container)
          (let [handler-type (gui/get-menu-type :fabric-1.20.1 gui-id)]
            (when-not handler-type
              (throw (ex-info "ScreenHandlerType not registered" {:gui-id gui-id})))
            (menu-bridge/create-menu-bridge sync-id handler-type clj-container)))))

    (writeScreenOpeningData [_ player buf]
      (.writeInt buf gui-id)
      (if tile-entity
        (let [pos (:pos tile-entity)]
          (.writeBoolean buf true)
          (.writeBlockPos buf pos))
        (.writeBoolean buf false)))))

(defn create-screen-handler-factory
  "Backward-compatible alias."
  [gui-id tile-entity]
  (create-menu-provider gui-id tile-entity))

(defn create-extended-screen-handler-factory
  "Backward-compatible alias."
  [gui-id tile-entity]
  (create-extended-menu-provider gui-id tile-entity))
