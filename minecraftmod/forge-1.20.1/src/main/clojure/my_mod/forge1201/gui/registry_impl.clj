(ns my-mod.forge1201.gui.registry-impl
  "Forge 1.20.1 GUI Registration Implementation"
  (:require [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.forge1201.gui.bridge :as bridge]
            [my-mod.util.log :as log])
  (:import [net.minecraftforge.network NetworkHooks]
           [net.minecraft.world.inventory MenuType]
           [net.minecraftforge.registries ForgeRegistries]
           [net.minecraft.resources ResourceLocation]))

(defonce node-menu-type (atom nil))
(defonce matrix-menu-type (atom nil))

(defn create-menu-type [gui-id]
  (MenuType.
    (reify java.util.function.BiFunction
      (apply [_ window-id player-inventory]
        (let [handler (gui-registry/get-gui-handler)
              player (.player player-inventory)
              world (.level player)
              pos (.blockPosition player)
              clj-container (.get-server-container handler gui-id player world pos)]
          (if clj-container
            (bridge/wrap-clojure-container window-id @node-menu-type clj-container)
            (do (log/error "Failed to create container for GUI" gui-id) nil)))))))

(defn register-menu-types! []
  (log/info "Registering Wireless GUI menu types for Forge 1.20.1")
  (reset! node-menu-type (create-menu-type gui-registry/gui-wireless-node))
  (reset! matrix-menu-type (create-menu-type gui-registry/gui-wireless-matrix))
  
  (let [node-loc (ResourceLocation. "my_mod" "wireless_node_gui")
        matrix-loc (ResourceLocation. "my_mod" "wireless_matrix_gui")]
    (.setRegistryName @node-menu-type node-loc)
    (.setRegistryName @matrix-menu-type matrix-loc)
    (.register ForgeRegistries/MENUS node-loc @node-menu-type)
    (.register ForgeRegistries/MENUS matrix-loc @matrix-menu-type)
    (log/info "Registered menu types: wireless_node_gui, wireless_matrix_gui")))

(defn open-gui-for-player [player gui-id tile-entity]
  (log/info "Opening GUI" gui-id "for player" (.getName player))
  (try
    (let [provider (bridge/create-menu-provider gui-id tile-entity)]
      (NetworkHooks/openScreen player provider)
      (log/info "GUI opened successfully"))
    (catch Exception e
      (log/error "Failed to open GUI:" (.getMessage e))
      (.printStackTrace e))))

(defmethod gui-registry/register-gui-handler :forge-1.20.1 [_]
  (log/info "Registering GUI handler for Forge 1.20.1")
  (register-menu-types!)
  (log/info "Forge 1.20.1 GUI handler registered"))

(defn open-node-gui-forge [player world pos]
  (let [tile-entity (.getBlockEntity world pos)]
    (open-gui-for-player player gui-registry/gui-wireless-node tile-entity)))

(defn open-matrix-gui-forge [player world pos]
  (let [tile-entity (.getBlockEntity world pos)]
    (open-gui-for-player player gui-registry/gui-wireless-matrix tile-entity)))

(defn init! []
  (log/info "Initializing Forge 1.20.1 GUI system")
  (gui-registry/register-gui-handler :forge-1.20.1)
  (log/info "Forge 1.20.1 GUI system initialized"))

(def NODE_MENU_TYPE node-menu-type)
(def MATRIX_MENU_TYPE matrix-menu-type)
