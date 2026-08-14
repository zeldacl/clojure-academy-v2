(ns cn.li.fabric262.client.presentation-hud-renderer
  "Fabric 26.2 HUD extraction hook for the unified Presentation Runtime."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.platform.neutral.presentation :as presentation]
            [cn.li.mc262.presentation.backend :as presentation-backend])
  (:import [net.fabricmc.fabric.api.client.rendering.v1.hud HudElement HudElementRegistry]
           [net.minecraft.resources Identifier]
           [net.minecraft.client Minecraft DeltaTracker]
           [net.minecraft.client.gui GuiGraphicsExtractor]))

(defn on-mode-switch-key-state! [_ & _] nil)
(defn- extract-hud!
  [^GuiGraphicsExtractor graphics ^DeltaTracker delta]
  (let [^Minecraft mc (Minecraft/getInstance)
        width (.guiWidth graphics)
        height (.guiHeight graphics)]
    (when-let [player (.player mc)]
      (presentation/ensure-combat-hud! (str (.getUUID player)) width height))
    (presentation/submit-current-frame!
      :hud (.getGameTimeDeltaPartialTick delta true)
      width height graphics)
    nil))

(defn init! []
  (presentation/register-backend! (presentation-backend/create))
  (presentation/ensure-registered!)
  (install/process-once!
    :fabric262/presentation-hud
    #(HudElementRegistry/addLast
       (Identifier/fromNamespaceAndPath "academy" "presentation_hud")
       (reify HudElement
         (extractRenderState [_ graphics delta]
           (extract-hud! graphics delta)))))
  nil)
