(ns cn.li.fabric1201.gui.screen-impl
  "Fabric 1.20.1 Client-side Screen Implementation

  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register, eliminating hardcoded game concepts."
  (:require [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui.screens MenuScreens]))

(defn register-screens! []
  (log/info "Registering GUI screens for Fabric 1.20.1")
  (try
    (let [platform :fabric-1.20.1]
      (doseq [gui-id (gui/get-all-gui-ids)]
        (let [menu-type (gui/get-menu-type platform gui-id)
              factory-fn-kw (gui/get-screen-factory-fn-kw gui-id)]
          (when (and menu-type factory-fn-kw)
            (let [factory-fn (ns-resolve 'cn.li.ac.gui.platform-adapter factory-fn-kw)]
              (if factory-fn
                (do
                  (MenuScreens/register
                    menu-type
                    (reify net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor
                      (create [_ menu player-inventory title]
                        (factory-fn menu player-inventory title))))
                  (log/info "Registered screen factory for GUI ID" gui-id))
                (log/warn "Screen factory function not found:" factory-fn-kw "for GUI ID" gui-id)))))))
    (log/info "Screen factories registered successfully (Fabric)")
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

(defn init-client! []
  (log/info "Initializing Fabric 1.20.1 client GUI system")
  (register-screens!)
  (log/info "Fabric 1.20.1 client GUI system initialized"))
