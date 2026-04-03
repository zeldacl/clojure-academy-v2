(ns cn.li.forge1201.client.ability-hud-bridge
  "CLIENT-ONLY HUD rendering bridge (Forge layer)."
  (:require [cn.li.ac.ability.client.hud-renderer :as ac-hud]
            [cn.li.ac.ability.player-state :as ps]
            [cn.li.forge1201.client.ability-hud :as hud]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.client.event RenderGuiOverlayEvent$Post]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.eventbus.api EventPriority]))

(set! *warn-on-reflection* true)

(defn- render-bar
  "Render a progress bar (CP or overload)."
  [^GuiGraphics graphics bar-data]
  (let [{:keys [x y width height percent texture]} bar-data
        filled-width (int (* width percent))
        tex-loc (ResourceLocation. ^String texture)]
    ;; Render background (empty bar)
    (.blit graphics tex-loc x y 0 0 width height)
    ;; Render filled portion
    (when (pos? filled-width)
      (.blit graphics tex-loc x y 0 height filled-width height))))

(defn- render-activation-indicator
  "Render activation status indicator."
  [^GuiGraphics graphics indicator-data]
  (let [{:keys [x y activated]} indicator-data]
    (when activated
      ;; Draw a simple colored circle or text
      (.drawString graphics "●" x y 0x00FF00))))

(defn- render-skill-slot
  "Render a single skill slot with icon, name, and cooldown overlay."
  [^GuiGraphics graphics slot-data]
  (let [{:keys [x y key-label skill-icon skill-name in-cooldown cooldown-seconds]} slot-data]
    ;; Render key hint background
    (.fill graphics x y (+ x 20) (+ y 20) 0x80000000)

    ;; Render key label
    (.drawString graphics key-label (+ x 2) (+ y 2) 0xFFFFFF)

    ;; Render skill icon (if texture exists)
    (when skill-icon
      (try
        (let [icon-loc (ResourceLocation. ^String skill-icon)]
          (.blit graphics icon-loc (+ x 25) y 0 0 16 16 16 16))
        (catch Exception _e
          ;; Fallback: just show skill name
          nil)))

    ;; Render skill name
    (.drawString graphics skill-name (+ x 45) (+ y 6) 0xFFFFFF)

    ;; Render cooldown overlay and time
    (when in-cooldown
      ;; Semi-transparent black overlay
      (.fill graphics x y (+ x 20) (+ y 20) 0xC0000000)

      ;; Render cooldown time text (centered on icon)
      (when (pos? cooldown-seconds)
        (let [time-text (format "%.1fs" cooldown-seconds)]
          (.drawString graphics time-text (+ x 3) (+ y 10) 0xFFFFFF))))))

(defn- get-client-player-uuid
  "Get current client player UUID."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (.getUUID player))))

(defn- on-render-gui-overlay
  "Handle GUI overlay rendering event."
  [^RenderGuiOverlayEvent$Post event]
  (try
    (when-let [player-uuid (get-client-player-uuid)]
      (let [graphics (.getGuiGraphics event)
            ^Minecraft mc (Minecraft/getInstance)
            window (.getWindow mc)
            screen-width (.getGuiScaledWidth window)
            screen-height (.getGuiScaledHeight window)
            hud-model (hud/hud-model player-uuid)
            cooldown-data (when-let [state (ps/get-player-state player-uuid)]
                           (:cooldown-data state))
            render-data (ac-hud/build-hud-render-data hud-model screen-width screen-height cooldown-data)]

        (when render-data
          ;; Render CP bar
          (when-let [cp-bar (:cp-bar render-data)]
            (render-bar graphics cp-bar))

          ;; Render overload bar
          (when-let [ol-bar (:overload-bar render-data)]
            (render-bar graphics ol-bar))

          ;; Render activation indicator
          (when-let [indicator (:activation-indicator render-data)]
            (render-activation-indicator graphics indicator))

          ;; Render skill slots
          (doseq [slot (:skill-slots render-data)]
            (when slot
              (render-skill-slot graphics slot))))))
    (catch Exception e
      (log/error "Error rendering ability HUD" e))))

(defn init!
  "Initialize HUD rendering system."
  []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL
                false
                RenderGuiOverlayEvent$Post
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-render-gui-overlay evt))))
  (log/info "Ability HUD renderer initialized"))
