(ns cn.li.neoforge262.client.presentation-hud-renderer
  "NeoForge 26.2 HUD callback for the unified Presentation Runtime."
  (:require [cn.li.mcbase.client.session :as client-session]
            [cn.li.platform.neutral.presentation :as presentation]
            [cn.li.mc262.presentation.backend :as presentation-backend]
            [cn.li.mcmod.util.log :as log])
  (:import [net.neoforged.neoforge.client.event RenderGuiEvent$Post]
           [net.neoforged.neoforge.common NeoForge]
           [net.neoforged.bus.api EventPriority]
           [net.minecraft.client Minecraft]
           [cn.li.neoforge262.bridge ClientTimeInterop]))

(defn on-mode-switch-key-state! [& _] nil)

(defn- on-render-gui [^RenderGuiEvent$Post event]
  (let [^Minecraft mc (Minecraft/getInstance)
        w (.getGuiScaledWidth (.getWindow mc))
        h (.getGuiScaledHeight (.getWindow mc))
        pt (ClientTimeInterop/getFrameTime mc)]
    (client-session/with-current-client-session
      #(do
         (when-let [player (.player mc)]
           (presentation/ensure-combat-hud!
             (str (.getUUID player)) w h))
         (presentation/submit-current-frame! :hud (float pt) w h
                                             (.getGuiGraphics event))))))

(defn init! []
  (presentation/register-backend! (presentation-backend/create))
  (.addListener (NeoForge/EVENT_BUS)
                EventPriority/NORMAL false RenderGuiEvent$Post
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-render-gui evt))))
  (log/info "Presentation HUD renderer initialized (NeoForge 26.2)"))
