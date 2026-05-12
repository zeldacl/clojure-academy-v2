(ns cn.li.forge1201.gui.screen-impl
  "Forge 1.20.1 Client-side Screen Implementation.

  The proxy remains Forge-specific, but the rendering/input plumbing is split
  into smaller helpers so the lifecycle is easier to follow and test."
  (:require [cn.li.mc1201.gui.screen-registry :as screen-registry]
            [cn.li.mc1201.gui.screen-impl-core :as screen-core]
            [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui.screens Screen]
           [cn.li.forge1201.shim ForgeClientHelper ForgeClientHelper$ScreenFactory]
           [net.minecraftforge.client.event ScreenEvent$BackgroundRendered]
           [net.minecraftforge.common MinecraftForge]))

(defn- register-one-screen!
  [gui-id menu-type factory-fn-kw]
  (log/info "[SCREEN-INIT] Registering GUI ID:" gui-id "menu-type:" menu-type "factory-fn-kw:" factory-fn-kw)
  (when menu-type
    (ForgeClientHelper/registerMenuScreen
     menu-type
     (reify ForgeClientHelper$ScreenFactory
       (create [_ menu player-inventory title]
         (log/info "[SCREEN-FACTORY] Creating screen for GUI ID" gui-id "factory-fn-kw:" factory-fn-kw)
         (screen-core/create-screen-or-fallback
          gui-id
          menu
          player-inventory
          title
          factory-fn-kw
          {:on-render-tail! (fn [^Screen screen gg _mx _my _pt]
                              (.post MinecraftForge/EVENT_BUS (ScreenEvent$BackgroundRendered. screen gg)))})))))
  (log/info "Registered screen for GUI ID" gui-id))

(defn register-screens!
  "Register screen factories with Forge."
  []
  (log/info "Registering GUI screens for Forge 1.20.1")
  (try
    (screen-registry/register-all-screens!
     :forge-1.20.1
     gui/get-all-gui-ids
     gui/get-menu-type
     gui/get-screen-factory-fn-kw
     register-one-screen!)
    (log/info "Screen factories registered successfully")
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

(defn init-client!
  "Initialize client-side GUI system. Call during FMLClientSetupEvent."
  []
  (log/info "Initializing Forge 1.20.1 client GUI system")
  (register-screens!)
  (log/info "Forge 1.20.1 client GUI system initialized"))
