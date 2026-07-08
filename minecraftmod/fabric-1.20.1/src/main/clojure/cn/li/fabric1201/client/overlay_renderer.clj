(ns cn.li.fabric1201.client.overlay-renderer
  "CLIENT-ONLY Fabric overlay event adapter — renders via reactive overlay-host.
   Build/update fns come from the client bridge (installed by ac via merge-client-bridge!,
   keys :reactive-overlay-build / :reactive-overlay-update). Zero static ac dependency."
  (:require [cn.li.mc1201.gui.reactive.overlay-host :as overlay-host]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.rendering.v1 HudRenderCallback]
           [net.minecraft.client Minecraft]))

(def ^:private hud-listener-guard-lock
  (Object.))

(def ^:private ^:dynamic *hud-listener-registered?*
  false)

(defn- bridge-build-fn [w h]
  (client-bridge/call-adapter :reactive-overlay-build w h))

(defn- bridge-update-fn [rt]
  (client-bridge/call-adapter :reactive-overlay-update rt))

(defn on-mode-switch-key-state!
  ([_is-down]
   nil)
  ([_owner _is-down]
   nil))

(defn init!
  []
  (when-not (var-get #'*hud-listener-registered?*)
    (locking hud-listener-guard-lock
      (when-not (var-get #'*hud-listener-registered?*)
        (.register HudRenderCallback/EVENT
                   (reify HudRenderCallback
                     (onHudRender [_ graphics tick-delta]
                       (let [^Minecraft mc (Minecraft/getInstance)
                             w (.getGuiScaledWidth (.getWindow mc))
                             h (.getGuiScaledHeight (.getWindow mc))]
                         (overlay-host/update-overlay!
                           graphics "default" w h (float tick-delta)
                           bridge-build-fn bridge-update-fn)))))
        (alter-var-root #'*hud-listener-registered?* (constantly true)))))
  (log/info "Reactive overlay renderer initialized (Fabric)"))
