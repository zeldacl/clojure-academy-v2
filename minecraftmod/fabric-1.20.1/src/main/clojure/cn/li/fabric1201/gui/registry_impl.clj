(ns cn.li.fabric1201.gui.registry-impl
  "Fabric 1.20.1 GUI Registration Implementation"
  (:require [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.fabric1201.gui.bridge :as bridge]
            [cn.li.ac.wireless.gui.registry :as gui-registry]
            [cn.li.mcmod.gui.metadata :as gui-meta]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.resources ResourceLocation]
           [net.fabricmc.fabric.api.screenhandler.v1 ScreenHandlerRegistry]))

(defonce gui-handler-types
  ^{:doc "Map from GUI ID to registered MenuType instances"}
  (atom {}))

(defn get-handler-type [gui-id]
  (get @gui-handler-types gui-id))

(defn create-screen-handler-type [gui-id]
  (let [registry-name (gui/get-registry-name gui-id)]
    (ScreenHandlerRegistry/registerSimple
      (ResourceLocation. modid/MOD-ID registry-name)
      (reify net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry$SimpleClientHandlerFactory
        (create [_ sync-id player-inventory]
          (let [handler (gui/get-gui-handler)
                player (.player player-inventory)
                world (.level player)
                pos (.blockPosition player)
                clj-container (.get-server-container handler gui-id player world pos)]
            (if clj-container
              (bridge/wrap-clojure-container sync-id (get-handler-type gui-id) clj-container)
              (do
                (log/error "Failed to create container for GUI" gui-id)
                nil))))))))

(defn create-extended-screen-handler-type [gui-id]
  (let [registry-name (gui/get-registry-name gui-id)]
    (ScreenHandlerRegistry/registerExtended
      (ResourceLocation. modid/MOD-ID registry-name)
      (reify net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry$ExtendedClientHandlerFactory
        (create [_ sync-id player-inventory buf]
          (let [gui-id-from-buf (.readInt buf)
                has-tile (.readBoolean buf)
                pos (when has-tile (.readBlockPos buf))
                handler (gui-registry/get-gui-handler)
                player (.player player-inventory)
                world (.level player)
                clj-container (.get-server-container handler gui-id-from-buf player world pos)]
            (if clj-container
              (bridge/wrap-clojure-container sync-id (get-handler-type gui-id-from-buf) clj-container)
              (do
                (log/error "Failed to create container for GUI" gui-id-from-buf)
                nil))))))))

(defn register-screen-handler-types! []
  (log/info "Registering GUI screen handler types for Fabric 1.20.1")
  (doseq [gui-id (gui/get-all-gui-ids)]
    (let [handler-type (create-extended-screen-handler-type gui-id)
          registry-name (gui/get-registry-name gui-id)]
      (swap! gui-handler-types assoc gui-id handler-type)
      (gui-meta/register-menu-type! :fabric-1.20.1 gui-id handler-type)
      (log/info "Registered screen handler type:" registry-name "for GUI ID" gui-id)))
  (log/info "Registered" (count @gui-handler-types) "screen handler types"))

(defn open-gui-for-player [player gui-id tile-entity]
  (log/info "Opening GUI" gui-id "for player" (.getName player))
  (try
    (let [factory (bridge/create-extended-screen-handler-factory gui-id tile-entity)]
      (try
        (clojure.lang.Reflector/invokeInstanceMethod player "openHandledScreen" (object-array [factory]))
        (catch Exception _
          (clojure.lang.Reflector/invokeInstanceMethod player "openMenu" (object-array [factory]))))
      (log/info "GUI opened successfully"))
    (catch Exception e
      (log/error "Failed to open GUI:" (.getMessage e))
      (.printStackTrace e))))

(defmethod gui-registry/register-gui-handler :fabric-1.20.1 [_]
  (log/info "Registering GUI handler for Fabric 1.20.1")
  (register-screen-handler-types!)
  (log/info "Fabric 1.20.1 GUI handler registered"))

(defn init! []
  (log/info "Initializing Fabric 1.20.1 GUI system")
  (gui-registry/register-gui-handler :fabric-1.20.1)
  (log/info "Fabric 1.20.1 GUI system initialized"))
