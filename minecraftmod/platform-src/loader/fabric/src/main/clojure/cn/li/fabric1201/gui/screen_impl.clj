(ns cn.li.fabric1201.gui.screen-impl
  "Fabric 1.20.1 Client-side Screen Implementation

  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register, eliminating hardcoded game concepts.

  Uses shared CGUI runtime host path for :cgui-screen-container payloads."
  (:require [cn.li.mc1201.gui.screen.registry :as screen-registry]
            [cn.li.platform.target :as target]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui.screens MenuScreens]))

(defn- register-one-screen!
  [gui-id menu-type screen-creator _factory-fn-kw]
  (when menu-type
    (MenuScreens/register
      menu-type
      (reify net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor
        (create [_ menu player-inventory title]
          (screen-creator menu player-inventory title)))))
  (log/info "Registered screen factory for GUI ID" gui-id))

(defn register-screens! []
  (log/info "Registering GUI screens for Fabric 1.20.1")
  (try
    (screen-registry/register-platform-screens!
      (target/current-target-key!)
      {:label "Fabric 1.20.1"
       :register-menu-screen! register-one-screen!})
    (log/info "Screen factories registered successfully (Fabric)")
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

(defn init-client! []
  (log/info "Initializing Fabric 1.20.1 client GUI system")
  (register-screens!)
  (log/info "Fabric 1.20.1 client GUI system initialized"))
