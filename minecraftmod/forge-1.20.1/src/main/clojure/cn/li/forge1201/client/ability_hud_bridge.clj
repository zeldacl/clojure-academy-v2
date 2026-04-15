(ns cn.li.forge1201.client.ability-hud-bridge
  "CLIENT-ONLY HUD rendering bridge (Forge layer)."
  (:require [clojure.string :as str]
            [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.forge1201.client.ability-hud :as hud]
            [cn.li.forge1201.client.ability-client-state :as client-state]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.client.event RenderGuiOverlayEvent$Post]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.gui Font]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private mode-switch-key-down? (atom false))
(defonce ^:private showing-numbers? (atom false))
(defonce ^:private last-show-value-change-ms (atom 0))

(defn- now-ms [] (System/currentTimeMillis))

(defn on-mode-switch-key-state!
  "Mirror original CPBar behavior: key down starts number display; key up starts fade-out timer."
  [is-down]
  (let [was-down @mode-switch-key-down?
        now (now-ms)]
    (cond
      (and (not was-down) is-down)
      (do
        (reset! showing-numbers? true)
        (reset! last-show-value-change-ms now))

      (and was-down (not is-down))
      (do
        (reset! showing-numbers? false)
        (if (> (- now @last-show-value-change-ms) 400)
          (reset! last-show-value-change-ms now)
          (reset! last-show-value-change-ms 0))))
    (reset! mode-switch-key-down? (boolean is-down))))


(defn- draw-string!
  [^GuiGraphics graphics ^String text x y color]
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Font font (.-font mc)]
    (.drawString graphics font text (int x) (int y) (unchecked-int color))))

(defn- normalize-texture-path
  [path]
  (when (and path (not (str/blank? path)))
    (cond
      (str/includes? path ":") path
      (str/starts-with? path "textures/") (str "my_mod:" path)
      :else (str "my_mod:textures/" path))))

(defn- render-bar
  "Render a progress bar using separate background and foreground textures."
  [^GuiGraphics graphics bar-data]
  (let [{:keys [x y width height percent bg-texture fg-texture]} bar-data
        filled-width (int (* (double percent) width))
        bg-path (normalize-texture-path bg-texture)
        fg-path (normalize-texture-path fg-texture)]
    ;; Render background (full width)
    (when-let [bg-loc (and bg-path (ResourceLocation/tryParse bg-path))]
      (.blit graphics bg-loc (int x) (int y) 0 0 (int width) (int height) (int width) (int height)))
    ;; Render foreground fill clipped to fill width via scissor
    (when (and fg-path (pos? filled-width))
      (when-let [fg-loc (ResourceLocation/tryParse fg-path)]
        (.enableScissor graphics (int x) (int y) (int (+ x filled-width)) (int (+ y height)))
        (.blit graphics fg-loc (int x) (int y) 0 0 (int width) (int height) (int width) (int height))
        (.disableScissor graphics)))))

(defn- render-activation-indicator
  "Render activation status indicator."
  [^GuiGraphics graphics indicator-data]
  (let [{:keys [x y activated]} indicator-data]
    (when activated
      ;; Draw a simple colored circle or text
      (draw-string! graphics "*" x y 0x00FF00))))

(defn- render-skill-slot
  "Render a single skill slot with icon, name, and cooldown overlay."
  [^GuiGraphics graphics slot-data]
  (let [{:keys [x y key-label skill-icon skill-name in-cooldown cooldown-seconds]} slot-data]
    ;; Render key hint background
    (.fill graphics x y (+ x 20) (+ y 20) -2147483648)

    ;; Render key label
    (draw-string! graphics (str key-label) (+ x 2) (+ y 2) 0xFFFFFF)

    ;; Render skill icon (if texture exists)
    (when skill-icon
      (try
        (when-let [icon-loc (ResourceLocation/tryParse (normalize-texture-path skill-icon))]
          (.blit graphics icon-loc (+ x 25) y 0 0 16 16 16 16))
        (catch Exception _e
          ;; Fallback: just show skill name
          nil)))

    ;; Render skill name
    (draw-string! graphics (str skill-name) (+ x 45) (+ y 6) 0xFFFFFF)

    ;; Render cooldown overlay and time
    (when in-cooldown
      ;; Semi-transparent black overlay
      (.fill graphics x y (+ x 20) (+ y 20) -1073741824)

      ;; Render cooldown time text (centered on icon)
      (when (pos? cooldown-seconds)
        (let [time-text (format "%.1fs" cooldown-seconds)]
          (draw-string! graphics time-text (+ x 3) (+ y 10) 0xFFFFFF))))))

(defn- clamp01 ^double [^double v]
  (max 0.0 (min 1.0 v)))

(defn- argb
  [alpha r g b]
  (bit-or (bit-shift-left (int alpha) 24)
          (bit-shift-left (int r) 16)
          (bit-shift-left (int g) 8)
      (unchecked-int b)))

(defn- calc-number-alpha
  [hud-model]
  (let [overloaded? (not (get-in hud-model [:overload :fine]))
        now (now-ms)
        last-ts @last-show-value-change-ms
        dt (if (zero? last-ts) Long/MAX_VALUE (- now last-ts))]
    (cond
      overloaded? 0.0
      @showing-numbers? (clamp01 (/ (- dt 200.0) 400.0))
      (< dt 300.0) (clamp01 (- 1.0 (/ dt 300.0)))
      :else 0.0)))

(defn- render-resource-numbers
  [^GuiGraphics graphics hud-model]
  (let [alpha (calc-number-alpha hud-model)]
    (when (pos? alpha)
      (let [cp-cur (double (or (get-in hud-model [:cp :cur]) 0.0))
            cp-max (double (or (get-in hud-model [:cp :max]) 0.0))
            ol-cur (double (or (get-in hud-model [:overload :cur]) 0.0))
            ol-max (double (or (get-in hud-model [:overload :max]) 0.0))
            color (argb (int (* 255.0 alpha)) 255 255 255)
            cp-text (format "CP %.0f/%.0f" cp-cur cp-max)
            ol-text (format "OL %.0f/%.0f" ol-cur ol-max)]
        (draw-string! graphics cp-text 120 10 color)
        (draw-string! graphics ol-text 120 24 color)))))

(defn- get-client-player-uuid
  "Get current client player UUID as string (consistent with server-sync key format)."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

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
            raw-hud-model (hud/hud-model player-uuid)
            ;; Apply client overlay for immediate activation feedback (bypasses shared atom race)
            hud-model (if-let [ov @client-state/client-activated-overlay]
                        (when raw-hud-model (assoc raw-hud-model :activated ov))
                        raw-hud-model)
            cooldown-data (when-let [state (ability-runtime/get-player-state player-uuid)]
                           (:cooldown-data state))
            render-data (ability-runtime/client-build-hud-render-data hud-model screen-width screen-height cooldown-data)]

        ;; Original behavior: HUD is visible only in activated ability mode.
        (when (and render-data (:activated hud-model))
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
              (render-skill-slot graphics slot)))

          ;; Render CP/OL numeric hints (V key feedback)
          (render-resource-numbers graphics hud-model))))
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
