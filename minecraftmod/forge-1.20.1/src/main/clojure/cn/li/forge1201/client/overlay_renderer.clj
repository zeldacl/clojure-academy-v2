(ns cn.li.forge1201.client.overlay-renderer
  "CLIENT-ONLY Forge overlay event adapter — renders via reactive overlay-host.
   Build/update fns come from the client bridge (installed by ac via merge-client-bridge!,
   keys :reactive-overlay-build / :reactive-overlay-update). Zero static ac dependency."
  (:require [cn.li.mc1201.gui.reactive.overlay-host :as overlay-host]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.client.event RenderGuiOverlayEvent$Post]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.client Minecraft]))

(defn- bridge-build-fn [w h]
  (client-bridge/call-adapter :reactive-overlay-build w h))

(defn- bridge-update-fn [rt]
  (client-bridge/call-adapter :reactive-overlay-update rt))

(defn- on-render-gui-overlay [^RenderGuiOverlayEvent$Post event]
  (let [^Minecraft mc (Minecraft/getInstance)
        w (.getGuiScaledWidth (.getWindow mc))
        h (.getGuiScaledHeight (.getWindow mc))
        pt (.getFrameTime mc)]
    (overlay-host/update-overlay!
      (.getGuiGraphics event) "default" w h pt
      bridge-build-fn bridge-update-fn)))

(defn on-mode-switch-key-state!
  ([is-down]
   nil)
  ([owner is-down]
   nil))

(defn init! []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false RenderGuiOverlayEvent$Post
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-render-gui-overlay evt))))
  (log/info "Reactive overlay renderer initialized"))
