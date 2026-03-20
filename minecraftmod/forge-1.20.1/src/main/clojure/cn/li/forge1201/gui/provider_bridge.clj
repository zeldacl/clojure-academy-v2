(ns cn.li.forge1201.gui.provider-bridge
  "Forge 1.20.1 provider bridge.

  Uses reify MenuProvider and delegates menu creation to menu-bridge."
  (:require [cn.li.mcmod.gui.platform-adapter :as gui]
            [cn.li.mcmod.util.log :as log]
            [cn.li.forge1201.gui.menu-bridge :as menu-bridge])
  (:import [net.minecraft.world MenuProvider]
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
  "Create a MenuProvider for opening GUI.

  Args:
  - gui-id: int (GUI identifier)
  - tile-entity: TileEntity instance

  Returns: MenuProvider instance"
  [gui-id tile-entity]
  (reify MenuProvider
    (getDisplayName [_]
      ;(Component/literal (gui/get-display-name gui-id))
      (Component/empty)
      )

    (createMenu [_ window-id player-inventory player]
      (let [handler (gui/get-gui-handler)
            world (.level player)
            pos (tile->pos tile-entity player)]
        (log/info "Creating menu for GUI" gui-id)
        (let [clj-container (.get-server-container handler gui-id player world pos)]
          (when-not clj-container
            (throw (ex-info "Failed to create Clojure container"
                            {:gui-id gui-id :player player})))
          (gui/register-active-container! clj-container)
          (gui/register-player-container! player clj-container)
          (let [menu-type (gui/get-menu-type :forge-1.20.1 gui-id)]
            (when-not menu-type
              (throw (ex-info "MenuType not registered" {:gui-id gui-id})))
            (menu-bridge/create-menu-bridge window-id menu-type clj-container)))))))

(defn create-extended-menu-provider
  "Forge has no separate extended provider type; keep a unified API."
  [gui-id tile-entity]
  (create-menu-provider gui-id tile-entity))