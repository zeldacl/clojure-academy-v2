(ns cn.li.forge1201.client.overlay-renderer
  "CLIENT-ONLY overlay executor. AC provides the overlay plan; Forge only draws it."
  (:require [clojure.string :as str]
            [cn.li.forge1201.client.overlay-state :as overlay-state]
            [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.client.event RenderGuiOverlayEvent$Post]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private mode-switch-key-down? (atom false))
(defonce ^:private showing-numbers? (atom false))
(defonce ^:private last-show-value-change-ms (atom 0))

(defn- now-ms [] (System/currentTimeMillis))

(defn on-mode-switch-key-state! [is-down]
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

(defn- draw-string! [^GuiGraphics graphics ^String text x y color]
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Font font (.-font mc)]
    (.drawString graphics font text (int x) (int y) (unchecked-int color))))

(defn- normalize-texture-path [path]
  (when (and path (not (str/blank? path)))
    (cond
      (str/includes? path ":") path
      (str/starts-with? path "textures/") (str "my_mod:" path)
      :else (str "my_mod:textures/" path))))

(defn- argb [{:keys [a r g b]}]
  (bit-or (bit-shift-left (int (or a 255)) 24)
          (bit-shift-left (int (or r 255)) 16)
          (bit-shift-left (int (or g 255)) 8)
          (int (or b 255))))

(defn- render-bar! [^GuiGraphics graphics {:keys [x y width height percent bg-texture fg-texture]}]
  (let [filled-width (int (* (double percent) width))
        bg-path (normalize-texture-path bg-texture)
        fg-path (normalize-texture-path fg-texture)]
    (when-let [bg-loc (and bg-path (ResourceLocation/tryParse bg-path))]
      (.blit graphics bg-loc (int x) (int y) 0 0 (int width) (int height) (int width) (int height)))
    (when (and fg-path (pos? filled-width))
      (when-let [fg-loc (ResourceLocation/tryParse fg-path)]
        (.enableScissor graphics (int x) (int y) (int (+ x filled-width)) (int (+ y height)))
        (.blit graphics fg-loc (int x) (int y) 0 0 (int width) (int height) (int width) (int height))
        (.disableScissor graphics)))))

(defn- render-skill-slot! [^GuiGraphics graphics {:keys [x y key-label skill-icon skill-name in-cooldown cooldown-seconds visual-state]}]
  (.fill graphics x y (+ x 20) (+ y 20)
         (case visual-state
           :charge -1442500609
           :active -1602223873
           -2147483648))
  (draw-string! graphics (str key-label) (+ x 2) (+ y 2) 0xFFFFFF)
  (when skill-icon
    (when-let [icon-loc (ResourceLocation/tryParse (normalize-texture-path skill-icon))]
      (.blit graphics icon-loc (+ x 25) y 0 0 16 16 16 16)))
  (draw-string! graphics (str skill-name) (+ x 45) (+ y 6) 0xFFFFFF)
  (when in-cooldown
    (.fill graphics x y (+ x 20) (+ y 20) -1073741824)
    (when (pos? cooldown-seconds)
      (draw-string! graphics (format "%.1fs" cooldown-seconds) (+ x 3) (+ y 10) 0xFFFFFF))))

(defn- render-element! [^GuiGraphics graphics element screen-width screen-height]
  (case (:kind element)
    :bar (render-bar! graphics element)
    :activation-indicator (when (:activated element)
                            (draw-string! graphics "*" (:x element) (:y element) 0x00FF00))
    :skill-slot (render-skill-slot! graphics element)
    :text (draw-string! graphics (str (:text element)) (:x element) (:y element)
                        (if (map? (:color element)) (argb (:color element)) (:color element)))
    :fill (.fill graphics (:x element) (:y element) (+ (:x element) (:w element)) (+ (:y element) (:h element))
                 (if (map? (:color element)) (argb (:color element)) (:color element)))
    :fullscreen-fill (.fill graphics 0 0 (int screen-width) (int screen-height)
                            (if (map? (:color element)) (argb (:color element)) (:color element)))
    nil))

(defn- get-client-player-uuid []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn- on-render-gui-overlay [^RenderGuiOverlayEvent$Post event]
  (try
    (when-let [player-uuid (get-client-player-uuid)]
      (let [graphics (.getGuiGraphics event)
            ^Minecraft mc (Minecraft/getInstance)
            window (.getWindow mc)
            screen-width (.getGuiScaledWidth window)
            screen-height (.getGuiScaledHeight window)
            overlay-plan (ability-runtime/client-build-overlay-plan
                           player-uuid
                           screen-width
                           screen-height
                           {:activated-override @overlay-state/client-activated-overlay
                            :showing-numbers? @showing-numbers?
                            :last-show-value-change-ms @last-show-value-change-ms
                            :now-ms (now-ms)})]
        (doseq [element (:elements overlay-plan)]
          (render-element! graphics element screen-width screen-height))))
    (catch Exception e
      (log/error "Error rendering overlay" e))))

(defn init! []
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/NORMAL false RenderGuiOverlayEvent$Post
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-render-gui-overlay evt))))
  (log/info "Overlay renderer initialized"))