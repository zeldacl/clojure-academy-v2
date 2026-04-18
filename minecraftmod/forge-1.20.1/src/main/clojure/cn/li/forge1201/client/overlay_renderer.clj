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

(defn- rgb-vec->argb
  "Convert [r g b] (0-255) to ARGB int with alpha."
  [[r g b] alpha]
  (bit-or (bit-shift-left (int (* 255 (double alpha))) 24)
          (bit-shift-left (int r) 16)
          (bit-shift-left (int g) 8)
          (int b)))

(defn- render-bar! [^GuiGraphics graphics {:keys [x y width height percent bg-texture fg-texture bar-color hint-percent]}]
  (let [filled-width (int (* (double percent) width))
        bg-path (normalize-texture-path bg-texture)
        fg-path (normalize-texture-path fg-texture)]
    (when-let [bg-loc (and bg-path (ResourceLocation/tryParse bg-path))]
      (.blit graphics bg-loc (int x) (int y) 0 0 (int width) (int height) (int width) (int height)))
    ;; Consumption hint ghost line
    (when (and hint-percent (pos? hint-percent) (< hint-percent percent))
      (let [hint-x (int (+ x (* (double hint-percent) width)))]
        (.fill graphics hint-x (int y) (+ hint-x 1) (int (+ y height))
               (unchecked-int 0x80FF4444))))
    (when (and fg-path (pos? filled-width))
      ;; Apply bar-color tint if provided (bar-color is a {:r :g :b :a} map)
      (if bar-color
        (let [color-int (argb bar-color)]
          (.enableScissor graphics (int x) (int y) (int (+ x filled-width)) (int (+ y height)))
          (.fill graphics (int x) (int y) (int (+ x filled-width)) (int (+ y height)) (unchecked-int color-int))
          (.disableScissor graphics))
        (when-let [fg-loc (ResourceLocation/tryParse fg-path)]
          (.enableScissor graphics (int x) (int y) (int (+ x filled-width)) (int (+ y height)))
          (.blit graphics fg-loc (int x) (int y) 0 0 (int width) (int height) (int width) (int height))
          (.disableScissor graphics))))))

(defn- render-skill-slot! [^GuiGraphics graphics {:keys [x y key-label skill-icon skill-name in-cooldown cooldown-seconds visual-state alpha glow-color sin-effect?]}]
  (let [effective-alpha (double (or alpha 1.0))
        bg-color (case visual-state
                   :charge (rgb-vec->argb (or glow-color [255 173 55]) (* 0.4 effective-alpha))
                   :active (rgb-vec->argb (or glow-color [70 179 255]) (* 0.4 effective-alpha))
                   (unchecked-int (rgb-vec->argb [0 0 0] (* 0.5 effective-alpha))))]
    (.fill graphics x y (+ x 20) (+ y 20) (unchecked-int bg-color))
    ;; Glow border for active/charge states
    (when (and glow-color (not= visual-state :idle))
      (let [border-alpha (if sin-effect?
                           (* 0.6 effective-alpha (+ 0.5 (* 0.5 (Math/sin (* (/ (System/currentTimeMillis) 300.0) Math/PI)))))
                           (* 0.6 effective-alpha))
            border-color (rgb-vec->argb glow-color border-alpha)]
        (.fill graphics (dec x) (dec y) (+ x 21) y (unchecked-int border-color))       ;; top
        (.fill graphics (dec x) (+ y 20) (+ x 21) (+ y 21) (unchecked-int border-color)) ;; bottom
        (.fill graphics (dec x) y x (+ y 20) (unchecked-int border-color))               ;; left
        (.fill graphics (+ x 20) y (+ x 21) (+ y 20) (unchecked-int border-color)))))   ;; right
  (draw-string! graphics (str key-label) (+ x 2) (+ y 2) 0xFFFFFF)
  (when skill-icon
    (when-let [icon-loc (ResourceLocation/tryParse (normalize-texture-path skill-icon))]
      (.blit graphics icon-loc (+ x 25) y 0 0 16 16 16 16)))
  (draw-string! graphics (str skill-name) (+ x 45) (+ y 6) 0xFFFFFF)
  (when in-cooldown
    (.fill graphics x y (+ x 20) (+ y 20) -1073741824)
    (when (pos? cooldown-seconds)
      (draw-string! graphics (format "%.1fs" cooldown-seconds) (+ x 3) (+ y 10) 0xFFFFFF))))

(defn- render-vec-reflection-crosshair!
  [^GuiGraphics graphics {:keys [x y phase intensity]}]
  (let [cx (int (or x 0))
        cy (int (or y 0))
        p (double (or phase 0.0))
        amp (double (or intensity 1.0))
        pulse (+ 1.0 (* 0.5 (Math/sin (* 2.0 Math/PI p))))
        radius (+ 11.0 (* 4.0 pulse amp))
        gap (+ 6 (int (* 2.0 pulse amp)))
        len (+ 8 (int (* 2.0 pulse amp)))
        line-color 0xB4E8F8FF
        ring-color 0x88DDF2FF]
    ;; Bracket-like reticle arms around the crosshair center.
    (.fill graphics (- cx len) (dec cy) (- cx gap) (inc cy) line-color)
    (.fill graphics (+ cx gap) (dec cy) (+ cx len) (inc cy) line-color)
    (.fill graphics (dec cx) (- cy len) (inc cx) (- cy gap) line-color)
    (.fill graphics (dec cx) (+ cy gap) (inc cx) (+ cy len) line-color)
    ;; Animated dotted ring wave.
    (doseq [idx (range 24)]
      (let [a (/ (* 2.0 Math/PI idx) 24.0)
            rx (+ cx (int (Math/round (* radius (Math/cos a)))))
            ry (+ cy (int (Math/round (* radius (Math/sin a)))))]
        (.fill graphics (dec rx) (dec ry) (inc rx) (inc ry) ring-color)))))

(defn- render-preset-indicator!
  "Render preset indicator: 4 small squares, glow on current, with fade."
  [^GuiGraphics graphics {:keys [x y current total fade]}]
  (let [square-size 8
        gap 3
        total-width (+ (* total square-size) (* (dec total) gap))
        start-x (- x (int (/ total-width 2)))
        alpha-byte (int (* 255 (double (or fade 1.0))))]
    (doseq [i (range total)]
      (let [sx (+ start-x (* i (+ square-size gap)))
            active? (= i current)
            color (if active?
                    (bit-or (bit-shift-left alpha-byte 24) 0x00FFAA00)
                    (bit-or (bit-shift-left (int (* alpha-byte 0.5)) 24) 0x00666666))]
        (.fill graphics sx y (+ sx square-size) (+ y square-size) (unchecked-int color))
        (when active?
          ;; Glow border
          (let [glow (bit-or (bit-shift-left alpha-byte 24) 0x00FFD700)]
            (.fill graphics (dec sx) (dec y) (+ sx square-size 1) y (unchecked-int glow))
            (.fill graphics (dec sx) (+ y square-size) (+ sx square-size 1) (+ y square-size 1) (unchecked-int glow))
            (.fill graphics (dec sx) y sx (+ y square-size) (unchecked-int glow))
            (.fill graphics (+ sx square-size) y (+ sx square-size 1) (+ y square-size) (unchecked-int glow))))))))

(defn- render-element! [^GuiGraphics graphics element screen-width screen-height]
  (case (:kind element)
    :bar (render-bar! graphics element)
    :activation-indicator (when (:activated element)
                            (draw-string! graphics "*" (:x element) (:y element) 0x00FF00)
                            ;; Render activate hint text if present
                            (when-let [hint (:hint element)]
                              (draw-string! graphics (str hint) (+ (:x element) 12) (:y element) 0xCCCCCC)))
    :skill-slot (render-skill-slot! graphics element)
    :vec-reflection-crosshair (render-vec-reflection-crosshair! graphics element)
    :preset-indicator (render-preset-indicator! graphics element)
    :overload-pulse (let [intensity (double (or (:intensity element) 0.0))
                          alpha (int (* 40 intensity))]
                      (when (pos? alpha)
                        (.fill graphics 0 0 (int screen-width) (int screen-height)
                               (unchecked-int (bit-or (bit-shift-left alpha 24) 0x00FF2200)))))
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