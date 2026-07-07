(ns cn.li.forge1201.client.overlay-renderer
  "CLIENT-ONLY Forge overlay event adapter — renders via reactive overlay-host."
  (:require [cn.li.mc1201.gui.reactive.overlay-host :as overlay-host]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.client.event RenderGuiOverlayEvent$Post]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.client Minecraft]))

(def ^:private active-overlay-build-fn (atom nil))
(def ^:private active-overlay-update-fn (atom nil))

(defn register-reactive-overlay!
  "Register reactive overlay build and update functions from ac layer."
  [build-fn update-fn]
  (reset! active-overlay-build-fn build-fn)
  (reset! active-overlay-update-fn update-fn))

(defn- on-render-gui-overlay [^RenderGuiOverlayEvent$Post event]
  (let [^Minecraft mc (Minecraft/getInstance)
        w (.getGuiScaledWidth (.getWindow mc))
        h (.getGuiScaledHeight (.getWindow mc))
        pt (.getFrameTime mc)]
    (overlay-host/update-overlay!
      (.getGuiGraphics event) "default" w h pt @active-overlay-update-fn)))

(defn on-mode-switch-key-state!
  ([is-down]
   (cn.li.mc1201.client.overlay.renderer/on-mode-switch-key-state! is-down))
  ([owner is-down]
   (cn.li.mc1201.client.overlay.renderer/on-mode-switch-key-state! owner is-down)))

(defn init! []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false RenderGuiOverlayEvent$Post
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-render-gui-overlay evt))))
  (log/info "Reactive overlay renderer initialized"))