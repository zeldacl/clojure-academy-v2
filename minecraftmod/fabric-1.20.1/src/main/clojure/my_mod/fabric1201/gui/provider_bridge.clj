(ns cn.li.fabric1201.gui.provider-bridge
  "Fabric 1.20.1 provider bridge.

  Uses reify factories and delegates menu construction to menu-bridge."
  (:require [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.mcmod.util.log :as log]
            [cn.li.fabric1201.gui.menu-bridge :as menu-bridge])
  (:import [net.minecraft.world MenuProvider]
           [net.fabricmc.fabric.api.screenhandler.v1 ExtendedScreenHandlerFactory]
           [net.minecraft.network.chat Component]))

(defn- tile->pos
  [tile-entity player]
  (cond
    (nil? tile-entity)
    (.blockPosition player)

    (map? tile-entity)
    (or (:pos tile-entity) (.blockPosition player))

    :else
    (try
      (clojure.lang.Reflector/invokeInstanceMethod tile-entity "getBlockPos" (object-array []))
      (catch Exception _
        (.blockPosition player)))))

(defn create-menu-provider
  "Create a simple MenuProvider provider."
  [gui-id tile-entity]
  (reify MenuProvider
    (getDisplayName [_]
      (Component/literal (gui/get-display-name gui-id)))

    (createMenu [_ sync-id player-inventory player]
      (let [handler (gui/get-gui-handler)
            world (.level player)
            pos (tile->pos tile-entity player)]
        (log/info "Creating menu for GUI" gui-id)
        (let [clj-container (.get-server-container handler gui-id player world pos)]
          (when-not clj-container
            (throw (ex-info "Failed to create Clojure container" {:gui-id gui-id})))
          (gui/register-active-container! clj-container)
          (gui/register-player-container! player clj-container)
          (let [menu-type (gui/get-menu-type :fabric-1.20.1 gui-id)]
            (when-not menu-type
              (throw (ex-info "MenuType not registered" {:gui-id gui-id})))
            (menu-bridge/create-menu-bridge sync-id menu-type clj-container)))))))

(defn create-extended-menu-provider
  "Create an ExtendedScreenHandlerFactory provider with packet opening data."
  [gui-id tile-entity]
  (reify ExtendedScreenHandlerFactory
    (getDisplayName [_]
      (Component/literal (gui/get-display-name gui-id)))

    (createMenu [_ sync-id player-inventory player]
      (let [handler (gui/get-gui-handler)
            world (.level player)
            pos (tile->pos tile-entity player)]
        (let [clj-container (.get-server-container handler gui-id player world pos)]
          (when-not clj-container
            (throw (ex-info "Failed to create Clojure container" {:gui-id gui-id})))
          (gui/register-active-container! clj-container)
          (gui/register-player-container! player clj-container)
          (let [menu-type (gui/get-menu-type :fabric-1.20.1 gui-id)]
            (when-not menu-type
              (throw (ex-info "MenuType not registered" {:gui-id gui-id})))
            (menu-bridge/create-menu-bridge sync-id menu-type clj-container)))))

    (writeScreenOpeningData [_ player buf]
      (.writeInt buf gui-id)
      (if tile-entity
        (let [pos (tile->pos tile-entity player)]
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
