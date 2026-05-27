(ns cn.li.forge1201.client.overlay-renderer
  "CLIENT-ONLY Forge overlay event adapter; rendering core lives in mc1201 shared layer."
  (:require [cn.li.mc1201.client.overlay.renderer :as shared-overlay]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.client.event RenderGuiOverlayEvent$Post]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]))

(defn- on-render-gui-overlay [^RenderGuiOverlayEvent$Post event]
  (shared-overlay/render-overlay! (.getGuiGraphics event)))

(defn on-mode-switch-key-state!
  ([is-down]
   (shared-overlay/on-mode-switch-key-state! is-down))
  ([owner is-down]
   (shared-overlay/on-mode-switch-key-state! owner is-down)))

(defn init! []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false RenderGuiOverlayEvent$Post
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-render-gui-overlay evt))))
  (log/info "Overlay renderer initialized"))