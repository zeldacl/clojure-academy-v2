(ns cn.li.fabric1211.client.presentation-hud-renderer
  "Fabric HUD callback for the unified Presentation Runtime."
  (:require [cn.li.mcbase.client.session :as client-session]
            [cn.li.platform.neutral.presentation :as presentation]
            [cn.li.mc1211.presentation.backend :as presentation-backend]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.rendering.v1 HudRenderCallback]
           [net.minecraft.client Minecraft]))

(defn on-mode-switch-key-state! [& _] nil)

(defn init! []
  (presentation/register-backend! (presentation-backend/create))
  (install/process-once! ::hud-listener-registered
    #(.register HudRenderCallback/EVENT
                (reify HudRenderCallback
                  (onHudRender [_ graphics tick-delta]
                    (let [^Minecraft mc (Minecraft/getInstance)
                          w (.getGuiScaledWidth (.getWindow mc))
                          h (.getGuiScaledHeight (.getWindow mc))]
                      (client-session/with-current-client-session
                        (fn []
                          (when-let [player (.player mc)]
                            (presentation/ensure-combat-hud!
                              (str (.getUUID player)) w h))
                          (presentation/submit-current-frame!
                            :hud (float tick-delta) w h graphics))))))))
  (log/info "Presentation HUD renderer initialized (Fabric)"))
