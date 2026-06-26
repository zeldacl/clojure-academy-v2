(ns cn.li.mc1201.client.overlay.renderer
  "Shared overlay rendering core (Minecraft-only)."
  (:require [clojure.string :as str]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mcmod.hooks.core :as client-ui]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.systems RenderSystem]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.resources ResourceLocation]))

(def ^:private default-overlay-render-runtime-state
  {:mode-switch-key-down? {}
   :showing-numbers? {}
   :last-show-value-change-ms {}
   :background-mask-color {}       ;; per-owner: {:r :g :b :a :last-ms}
   :smoothed-cp-percent {}         ;; per-owner: float
   :smoothed-overload-percent {}   ;; per-owner: float
   :smoothed-last-update-ms-cp {}
   :smoothed-last-update-ms-ol {}})

(defn create-overlay-render-runtime
  []
  {::runtime ::overlay-render-runtime
   :runtime-state* (atom default-overlay-render-runtime-state)})

(def ^:dynamic *overlay-render-runtime* nil)

(defonce ^:private installed-overlay-render-runtime
  (create-overlay-render-runtime))

(defn- overlay-render-runtime?
  [runtime]
  (and (map? runtime)
       (= ::overlay-render-runtime (::runtime runtime))
       (some? (:runtime-state* runtime))))

(defn call-with-overlay-render-runtime
  [runtime f]
  (when-not (overlay-render-runtime? runtime)
    (throw (ex-info "Expected overlay render runtime"
                    {:runtime runtime})))
  (binding [*overlay-render-runtime* runtime]
    (f)))

(defmacro with-overlay-render-runtime
  [runtime & body]
  `(call-with-overlay-render-runtime ~runtime (fn [] ~@body)))

(defn- current-overlay-render-runtime
  []
  (or *overlay-render-runtime*
      installed-overlay-render-runtime))

(defn- overlay-render-runtime-state-atom
  []
  (:runtime-state* (current-overlay-render-runtime)))

(defn- overlay-render-runtime-state-snapshot
  []
  @(overlay-render-runtime-state-atom))

(defn- update-overlay-render-runtime!
  [f & args]
  (apply swap! (overlay-render-runtime-state-atom) f args))

(defn- now-ms []
  "Game-time milliseconds (pauses when game pauses). Falls back to wall-clock."
  (if-let [level (.level (Minecraft/getInstance))]
    (* (.getGameTime level) 50)
    (System/currentTimeMillis)))

(defn- render-owner-key
  [owner]
  (client-session/owner-key owner))

(defn- owner-state
  [state-key owner default]
  (get-in (overlay-render-runtime-state-snapshot) [state-key (render-owner-key owner)] default))

(defn- assoc-owner-state!
  [state-key owner value]
  (update-overlay-render-runtime! assoc-in [state-key (render-owner-key owner)] value)
  nil)

(defn clear-overlay-render-state!
  [owner]
  (let [owner-key (render-owner-key owner)]
    (update-overlay-render-runtime!
      (fn [runtime-state]
        (-> runtime-state
            (update :mode-switch-key-down? dissoc owner-key)
            (update :showing-numbers? dissoc owner-key)
            (update :last-show-value-change-ms dissoc owner-key)
            (update :background-mask-color dissoc owner-key)
            (update :smoothed-cp-percent dissoc owner-key)
            (update :smoothed-overload-percent dissoc owner-key)
            (update :smoothed-last-update-ms-cp dissoc owner-key)
            (update :smoothed-last-update-ms-ol dissoc owner-key))))
  nil))

(defn clear-overlay-render-session!
  [client-session-id]
  (let [clear-session-state
        (fn [states]
          (into {}
                (remove (fn [[[entry-session-id _player-uuid] _value]]
                          (= client-session-id entry-session-id)))
                states))]
    (update-overlay-render-runtime!
      (fn [runtime-state]
        (-> runtime-state
            (update :mode-switch-key-down? clear-session-state)
            (update :showing-numbers? clear-session-state)
            (update :last-show-value-change-ms clear-session-state)
            (update :background-mask-color clear-session-state)
            (update :smoothed-cp-percent clear-session-state)
            (update :smoothed-overload-percent clear-session-state)
            (update :smoothed-last-update-ms-cp clear-session-state)
            (update :smoothed-last-update-ms-ol clear-session-state)))))
  nil)

(defn overlay-render-state-snapshot
  []
  (overlay-render-runtime-state-snapshot))

(defn reset-overlay-render-state-for-test!
  ([]
   (reset-overlay-render-state-for-test! {}))
  ([{:keys [mode-switch-key-down
       showing-numbers
       last-show-value-change
       background-mask-color
       smoothed-cp-percent
       smoothed-overload-percent
       smoothed-last-update-ms-cp
       smoothed-last-update-ms-ol]
     :or {mode-switch-key-down {}
          showing-numbers {}
          last-show-value-change {}
          background-mask-color {}
          smoothed-cp-percent {}
          smoothed-overload-percent {}
          smoothed-last-update-ms-cp {}
          smoothed-last-update-ms-ol {}}}]
   (reset! (overlay-render-runtime-state-atom)
           {:mode-switch-key-down? mode-switch-key-down
            :showing-numbers? showing-numbers
            :last-show-value-change-ms last-show-value-change
            :background-mask-color background-mask-color
            :smoothed-cp-percent smoothed-cp-percent
            :smoothed-overload-percent smoothed-overload-percent
            :smoothed-last-update-ms-cp smoothed-last-update-ms-cp
            :smoothed-last-update-ms-ol smoothed-last-update-ms-ol})
   nil))

(defn on-mode-switch-key-state!
  ([is-down]
   (when-let [owner (client-session/current-local-player-owner)]
     (on-mode-switch-key-state! owner is-down)))
  ([owner is-down]
   (let [was-down (boolean (owner-state :mode-switch-key-down? owner false))
         last-change (long (owner-state :last-show-value-change-ms owner 0))
         now (now-ms)]
     (cond
       (and (not was-down) is-down)
       (do
         (assoc-owner-state! :showing-numbers? owner true)
         (assoc-owner-state! :last-show-value-change-ms owner now))

       (and was-down (not is-down))
       (do
         (assoc-owner-state! :showing-numbers? owner false)
         (if (> (- now last-change) 400)
           (assoc-owner-state! :last-show-value-change-ms owner now)
           (assoc-owner-state! :last-show-value-change-ms owner 0))))
     (assoc-owner-state! :mode-switch-key-down? owner (boolean is-down)))))

;; --- BackgroundMask: smooth color transition ---

(defn- smooth-mask-color
  "Lerp each RGBA channel toward target at rate 1.0/sec. Returns updated {:r :g :b :a :last-ms}."
  [cur-color target now-ms]
  (let [{:keys [r g b a last-ms]} cur-color
        tr (:r target) tg (:g target) tb (:b target) ta (:a target 0.0)
        dt (/ (double (- now-ms (long (or last-ms 0)))) 1000.0)
        rate 1.0
        lerp-chan (fn [from to]
                    (let [delta (double (- to from))]
                      (if (<= (Math/abs delta) 0.001)
                        to
                        (+ from (* (Math/signum delta) (min (* rate dt) (Math/abs delta)))))))]
    {:r (lerp-chan (double (or r 0.0)) tr)
     :g (lerp-chan (double (or g 0.0)) tg)
     :b (lerp-chan (double (or b 0.0)) tb)
     :a (lerp-chan (double (or a 0.0)) ta)
     :last-ms now-ms}))

;; --- CP/Overload buffered animation ---

(defn- smooth-percent
  "Smoothly transition current toward target at balance speed 2.0/sec."
  [current target last-ms now-ms]
  (let [dt (/ (double (- now-ms (long (or last-ms 0)))) 1000.0)
        rate 2.0
        delta (double (- target current))]
    (if (<= (Math/abs delta) 0.0005)
      target
      (+ current (* (Math/signum delta) (min (* rate dt) (Math/abs delta)))))))

;; --- Interference visual effects ---

(defn- jitter-offset [^long now-ms ^long axis-seed]
  (let [tick (quot now-ms 150)
        rng (java.util.Random. (+ tick (* axis-seed 65537)))]
    (* 3.0 (- (* 2.0 (.nextFloat rng)) 1.0))))

(defn- flicker-alpha [^long now-ms]
  (+ 0.5 (* 0.5 (Math/sin (* (double now-ms) 0.003)))))

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
  (unchecked-int
    (bit-or (bit-shift-left (unchecked-int (or a 255)) 24)
            (bit-shift-left (unchecked-int (or r 255)) 16)
            (bit-shift-left (unchecked-int (or g 255)) 8)
            (unchecked-int (or b 255)))))

(defn- rgb-vec->argb [[r g b] alpha]
  (unchecked-int
    (bit-or (bit-shift-left (unchecked-int (* 255 (double alpha))) 24)
            (bit-shift-left (unchecked-int r) 16)
            (bit-shift-left (unchecked-int g) 8)
            (unchecked-int b))))

(defn- interp-color-stops
  "Interpolate between nearest stops in color-stops list for a given percent [0,1].
  Each stop is a map with :pct (float 0-1) and :r :g :b color channels."
  [color-stops pct]
  (let [stops (sort-by :pct color-stops)
        pct (max 0.0 (min 1.0 (double pct)))
        first-stop (first stops)]
    (if (nil? first-stop)
      {:r 1.0 :g 1.0 :b 1.0 :a 255}                               ;; fallback: white
      (loop [[s1 s2 & more] stops]
        (cond
          (nil? s2) {:r (double (:r s1)) :g (double (:g s1))      ;; last stop reached
                     :b (double (:b s1)) :a 255}
          (<= (:pct s2) pct) (recur (cons s2 more))
          :else (let [t (/ (- pct (:pct s1)) (- (:pct s2) (:pct s1)))
                      r (+ (:r s1) (* t (- (:r s2) (:r s1))))
                      g (+ (:g s1) (* t (- (:g s2) (:g s1))))
                      b (+ (:b s1) (* t (- (:b s2) (:b s1))))]
                  {:r (double r) :g (double g) :b (double b) :a 255}))))))

(defn- render-bar!
  [^GuiGraphics graphics {:keys [x y width height percent bg-texture fg-texture bar-color hint-percent
                                  icon-cutout scroll-offset color-stops category-icon overloaded full-glow?]}]
  (let [filled-width (int (* (double percent) width))
        bg-path (normalize-texture-path bg-texture)
        fg-path (normalize-texture-path fg-texture)
        cutout-start (when icon-cutout (+ (int x) (int (:x-offset icon-cutout 0))))
        cutout-width (when icon-cutout (int (:w icon-cutout 0)))
        cutout-end (when icon-cutout (+ cutout-start cutout-width))]
    ;; Draw background texture
    (when-let [bg-loc (and bg-path (ResourceLocation/tryParse bg-path))]
      (.blit graphics bg-loc (int x) (int y) 0 0 (int width) (int height) (int width) (int height)))
    ;; Consumption hint line
    (when (and hint-percent (pos? hint-percent) (< hint-percent percent))
      (let [hint-x (int (+ x (* (double hint-percent) width)))]
        (.fill graphics hint-x (int y) (+ hint-x 1) (int (+ y height))
               (unchecked-int 0x80FF4444))))
    ;; Draw filled portion
    (when (pos? filled-width)
      (let [bar-start (int x)
            bar-end (int (+ x filled-width))]
        (if color-stops
          ;; Gradient rendering: segment the filled area into slices
          (let [segments 20
                seg-w (/ (double filled-width) (double segments))]
            (doseq [i (range segments)]
              (let [seg-start (int (+ (double x) (* (double i) seg-w)))
                    seg-end   (int (+ (double x) (* (double (inc i)) seg-w)))
                    seg-end   (min seg-end bar-end)
                    seg-mid-pct (/ (- (+ seg-start seg-end) (* 2 (int x))) (* 2.0 width))
                    seg-color (interp-color-stops color-stops seg-mid-pct)]
                (when (< seg-start seg-end)
                  ;; Apply cutout to gradient segments too
                  (let [eff-start (if (and cutout-start (< seg-start cutout-end) (> seg-end cutout-start))
                                    (if (< seg-start cutout-start) seg-start (max seg-start cutout-end))
                                    seg-start)
                        eff-end   (if (and cutout-start (< seg-start cutout-end) (> seg-end cutout-start))
                                    (if (> seg-end cutout-end) seg-end (min seg-end cutout-start))
                                    seg-end)]
                    (when (< eff-start eff-end)
                      (.fill graphics eff-start (int y) eff-end (int (+ y height))
                             (argb seg-color))))))))
          ;; Flat color or texture rendering (existing logic)
          (letfn [(draw-segment! [seg-start seg-end]
                    (when (< seg-start seg-end)
                      (if bar-color
                        (let [color-int (argb bar-color)]
                          (.enableScissor graphics (int seg-start) (int y) (int seg-end) (int (+ y height)))
                          (.fill graphics (int seg-start) (int y) (int seg-end) (int (+ y height)) (unchecked-int color-int))
                          (.disableScissor graphics))
                        (when-let [fg-loc (and fg-path (ResourceLocation/tryParse fg-path))]
                          (let [uoff (float (* (double (or scroll-offset 0.0)) width))]
                            (.enableScissor graphics (int seg-start) (int y) (int seg-end) (int (+ y height)))
                            (.blit graphics fg-loc (int x) (int y) uoff 0.0 (int width) (int height) (float width) (float height))
                            (.disableScissor graphics))))))]
            (if (and cutout-start cutout-width (pos? cutout-width))
              (do
                (draw-segment! bar-start (min bar-end cutout-start))
                (draw-segment! (max bar-start cutout-end) bar-end))
              (draw-segment! bar-start bar-end))))))
    ;; Draw category icon at cutout position (after bar fill, on top)
    (when (and category-icon (string? category-icon) (not (str/blank? category-icon))
               cutout-start cutout-width (pos? cutout-width))
      (when-let [icon-loc (ResourceLocation/tryParse (normalize-texture-path category-icon))]
        (let [cx (+ (int x) (:x-offset icon-cutout))
              cy (+ (int y) (quot (- (int height) 16) 2))]
          (.blit graphics icon-loc cx cy 0 0 16 16 16 16))))
    ;; Full-CP glow: pulsing white overlay when CP reaches 100%,
    ;; matching original AcademyCraft max-CP visual feedback.
    (when full-glow?
      (let [pulse (float (+ 0.2 (* 0.15 (Math/sin (* (System/currentTimeMillis) 0.004)))))
            a (int (* 255.0 pulse))]
        (.fill graphics (int x) (int y) (int (+ x width)) (int (+ y height))
               (unchecked-int (bit-or (bit-shift-left a 24) 0x00FFFFFF)))))
    ;; Overload highlight pulse (mimics cpbar_overload shader's highlight effect)
    (when overloaded
      (let [pulse-alpha (float (+ 0.3 (* 0.35 (Math/sin (* (System/currentTimeMillis) 0.003)))))
            a (int (* 255.0 pulse-alpha))]
        (.fill graphics (int x) (int y) (int (+ x width)) (int (+ y height))
               (unchecked-int (bit-or (bit-shift-left a 24)
                                      (bit-shift-left (int 255) 16)
                                      (bit-shift-left (int 77) 8)
                                      (int 25))))))))

(defn- render-content-slot!
  "Render a skill slot matching original AcademyCraft layout:
   - 20x20 background square for key label
   - 16x16 skill icon to the right of key label
   - Skill name text below the icon
   - Cooldown overlay covers icon from bottom-up
   - Glow borders on active/charge delegate states"
  [^GuiGraphics graphics {:keys [x y key-label content-icon content-label disabled? status-seconds
                                 visual-state alpha glow-color sin-effect?
                                 cooldown-total cooldown-remaining]}]
  (let [effective-alpha (double (or alpha 1.0))
        ;; Background for key label square
        bg-color (case visual-state
                   :charge (rgb-vec->argb (or glow-color [255 173 55]) (* 0.4 effective-alpha))
                   :active (rgb-vec->argb (or glow-color [70 179 255]) (* 0.4 effective-alpha))
                   (unchecked-int (rgb-vec->argb [0 0 0] (* 0.5 effective-alpha))))
        icon-x (+ x 14)
        icon-y y]
    ;; Key label background square (20x20)
    (.fill graphics x y (+ x 20) (+ y 20) (unchecked-int bg-color))
    ;; Glow borders for non-idle visual states (around key label square)
    (when (and glow-color (not= visual-state :idle))
      (let [border-alpha (if sin-effect?
                           (* 0.6 effective-alpha (+ 0.5 (* 0.5 (Math/sin (* (/ (System/currentTimeMillis) 300.0) Math/PI)))))
                           (* 0.6 effective-alpha))
            border-color (rgb-vec->argb glow-color border-alpha)]
        (.fill graphics (dec x) (dec y) (+ x 21) y (unchecked-int border-color))
        (.fill graphics (dec x) (+ y 20) (+ x 21) (+ y 21) (unchecked-int border-color))
        (.fill graphics (dec x) y x (+ y 20) (unchecked-int border-color))
        (.fill graphics (+ x 20) y (+ x 21) (+ y 20) (unchecked-int border-color))))
    ;; Key label text centered in the 20x20 square
    (draw-string! graphics (str key-label) (+ x 6) (+ y 6) 0xFFFFFF)
    ;; Skill icon (16x16 to the right of key label)
    (when content-icon
      (when-let [icon-loc (ResourceLocation/tryParse (normalize-texture-path content-icon))]
        (.blit graphics icon-loc icon-x icon-y 0 0 16 16 16 16))
      ;; Cooldown overlay: gray bar covers icon from bottom-up
      (when (and disabled? (pos? (long (or cooldown-total 0))))
        (let [remaining (double (or cooldown-remaining 0))
              total (double cooldown-total)
              progress (/ remaining total)
              overlay-h (int (* 16.0 progress))]
          (when (pos? overlay-h)
            (.fill graphics icon-x icon-y
                   (+ icon-x 16) (+ icon-y overlay-h)
                   (int 0x7F888888))))))  ;; 50% alpha gray
    ;; Skill name text below icon
    (draw-string! graphics (str content-label) (+ x 2) (+ y 21) 0xCCCCCC)
    ;; Disabled: full gray overlay on key label square + icon
    (when disabled?
      (.fill graphics x y (+ x 20) (+ y 20) -1073741824)
      (when (pos? (double (or status-seconds 0.0)))
        (draw-string! graphics (format "%.1fs" status-seconds) (+ x 3) (+ y 10) 0xFFFFFF)))))

(defn- render-crosshair-marker!
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
    (.fill graphics (- cx len) (dec cy) (- cx gap) (inc cy) line-color)
    (.fill graphics (+ cx gap) (dec cy) (+ cx len) (inc cy) line-color)
    (.fill graphics (dec cx) (- cy len) (inc cx) (- cy gap) line-color)
    (.fill graphics (dec cx) (+ cy gap) (inc cx) (+ cy len) line-color)
    (doseq [idx (range 24)]
      (let [a (/ (* 2.0 Math/PI idx) 24.0)
            rx (+ cx (int (Math/round (* radius (Math/cos a)))))
            ry (+ cy (int (Math/round (* radius (Math/sin a)))))]
        (.fill graphics (dec rx) (dec ry) (inc rx) (inc ry) ring-color)))))

(defn- render-selection-indicator!
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
          (let [glow (bit-or (bit-shift-left alpha-byte 24) 0x00FFD700)]
            (.fill graphics (dec sx) (dec y) (+ sx square-size 1) y (unchecked-int glow))
            (.fill graphics (dec sx) (+ y square-size) (+ sx square-size 1) (+ y square-size 1) (unchecked-int glow))
            (.fill graphics (dec sx) y sx (+ y square-size) (unchecked-int glow))
            (.fill graphics (+ sx square-size) y (+ sx square-size 1) (+ y square-size) (unchecked-int glow))))))))

(defn- render-blit-texture!
  [^GuiGraphics graphics {:keys [texture x y w h alpha u v tex-w tex-h]}]
  (when (and texture (pos? (int (or w 0))) (pos? (int (or h 0))))
    (when-let [loc (ResourceLocation/tryParse (normalize-texture-path texture))]
      (let [a (float (double (or alpha 1.0)))
            u (float (double (or u 0.0)))
            v (float (double (or v 0.0)))
            tw (float (double (or tex-w w)))
            th (float (double (or tex-h h)))]
        (RenderSystem/setShaderColor 1.0 1.0 1.0 a)
        (.blit graphics loc (int x) (int y) u v (int w) (int h) tw th)
        (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0)))))

(defn- render-element! [^GuiGraphics graphics element screen-width screen-height]
  (case (:kind element)
    :bar (render-bar! graphics element)
    :activation-indicator
    (let [activated? (boolean (:activated element))
          dot-size 6
          dot-x (:x element)
          dot-y (+ (:y element) 4)
          dot-color (if activated? 0xFF00CC00 0xFF555555)]
      ;; Small status dot: green = activated, gray = inactive
      (.fill graphics dot-x dot-y (+ dot-x dot-size) (+ dot-y dot-size) (unchecked-int dot-color))
      ;; Hint text
      (when-let [hint (:hint element)]
        (draw-string! graphics (str hint) (+ (:x element) 10) (:y element)
                      (if activated? 0xCCCCCC 0xFF888888))))
    :content-slot (render-content-slot! graphics element)
    :content-crosshair (render-crosshair-marker! graphics element)
    :selection-indicator (render-selection-indicator! graphics element)
    :blit-texture (render-blit-texture! graphics element)
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

(defn render-overlay!
  [^GuiGraphics graphics]
  (try
    (when-let [owner (client-session/current-local-player-owner)]
      (let [player-uuid (:player-uuid owner)
            ^Minecraft mc (Minecraft/getInstance)
            window (.getWindow mc)
            screen-width (.getGuiScaledWidth window)
            screen-height (.getGuiScaledHeight window)
            owner-key (render-owner-key owner)
            now (now-ms)
            overlay-plan (binding [client-ui/*client-session-id* (:client-session-id owner)]
                           (client-ui/client-build-overlay-plan
                             player-uuid
                             screen-width
                             screen-height
                             {:activated-override (overlay-state/get-client-activated owner)
                              :showing-numbers? (owner-state :showing-numbers? owner false)
                              :last-show-value-change-ms (owner-state :last-show-value-change-ms owner 0)
                              :now-ms now}))
            ;; --- BackgroundMask: smooth and render ---
            bg-mask-target (:background-mask overlay-plan)
            _ (when bg-mask-target
                (let [cur (get-in @(overlay-render-runtime-state-atom) [:background-mask-color owner-key]
                                  {:r 0.0 :g 0.0 :b 0.0 :a 0.0 :last-ms now})
                      smoothed (smooth-mask-color cur bg-mask-target now)]
                  (update-overlay-render-runtime! assoc-in [:background-mask-color owner-key] smoothed)
                  (when (> (:a smoothed) 0.01)
                    (.fill graphics 0 0 (int screen-width) (int screen-height)
                           (unchecked-int (bit-or (bit-shift-left (int (* 255.0 (double (:a smoothed)))) 24)
                                                  (bit-shift-left (int (* 255.0 (double (:r smoothed)))) 16)
                                                  (bit-shift-left (int (* 255.0 (double (:g smoothed)))) 8)
                                                  (int (* 255.0 (double (:b smoothed))))))))))
            ;; --- Interference: jitter pose + flicker alpha ---
            interfered? (boolean (:interfered? overlay-plan))
            pose (.pose graphics)
            _ (when interfered?
                (let [jx (jitter-offset now 0)
                      jy (jitter-offset now 1)
                      fa (float (flicker-alpha now))]
                  (.pushPose pose)
                  (.translate pose (double jx) (double jy) 0.0)
                  (RenderSystem/setShaderColor 1.0 1.0 1.0 fa)))
            ;; --- Smooth CP/Overload percents (pure transform, no side effects) ---
            elements (vec (:elements overlay-plan))
            elements (mapv (fn [elem]
                            (if (and (= :bar (:kind elem))
                                     (number? (:percent elem)))
                              (let [is-overload-bar (contains? elem :scroll-offset)
                                    smoothed-key (if is-overload-bar
                                                   :smoothed-overload-percent
                                                   :smoothed-cp-percent)
                                    last-ms-key (if is-overload-bar
                                                  :smoothed-last-update-ms-ol
                                                  :smoothed-last-update-ms-cp)
                                    last-ms (get-in @(overlay-render-runtime-state-atom) [last-ms-key owner-key] now)
                                    current (get-in @(overlay-render-runtime-state-atom) [smoothed-key owner-key]
                                                    (double (or (:percent elem) 0.0)))
                                    target (double (or (:percent elem) 0.0))
                                    smoothed (smooth-percent current target last-ms now)]
                                (update-overlay-render-runtime! assoc-in [smoothed-key owner-key] smoothed)
                                (update-overlay-render-runtime! assoc-in [last-ms-key owner-key] now)
                                (assoc elem :percent (double smoothed)))
                              elem))
                          elements)
            ;; --- Render elements ---
            _ (doseq [element elements]
                (render-element! graphics element screen-width screen-height))
            ;; --- Cleanup interference (guarded by finally logic via try/catch above) ---
            _ (when interfered?
                (.popPose pose)
                (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0))]
        nil))
    (catch Exception e
      (log/error "Error rendering overlay" e)
      ;; Best-effort cleanup: if interference pose was pushed, try to restore.
      (try (.popPose (.pose graphics)) (catch Exception _))
      (try (RenderSystem/setShaderColor 1.0 1.0 1.0 1.0) (catch Exception _)))))
