(ns cn.li.mc1201.client.screen.host
  "CLIENT-ONLY generic screen host. AC provides draw ops and interaction handlers."
  (:require [cn.li.mcmod.hooks.core :as client-ui]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics Font]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]
           [net.minecraft.resources ResourceLocation]))

(defn- draw-string! [^GuiGraphics graphics ^String text x y color]
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Font font (.-font mc)]
    (.drawString graphics font text (int x) (int y) (int color))))

(defn- normalize-texture-path [path]
  (when (and path (not (str/blank? path)))
    (cond
      (str/includes? path ":") path
      (str/starts-with? path "textures/") (str "my_mod:" path)
      :else (str "my_mod:textures/" path))))

(defn- render-op! [^GuiGraphics graphics op]
  (case (:kind op)
    :text (draw-string! graphics (str (:text op)) (:x op) (:y op) (:color op))
    :fill (.fill graphics (:x op) (:y op) (+ (:x op) (:w op)) (+ (:y op) (:h op)) (:color op))
    :icon-or-fill (if-let [loc (some-> (:texture op) normalize-texture-path ResourceLocation/tryParse)]
                    (.blit graphics loc (:x op) (:y op) 0 0 (:w op) (:h op) (:w op) (:h op))
                    (.fill graphics (:x op) (:y op) (+ (:x op) (:w op)) (+ (:y op) (:h op)) (:fallback-color op)))
    :progress-ring
    (let [segments (max 1 (int (or (:segments op) 24)))
          filled (int (max 0 (min segments (or (:filled-segments op) 0))))
          x (double (:x op))
          y (double (:y op))
          size (double (or (:size op) 20))
          cx (+ x (/ size 2.0))
          cy (+ y (/ size 2.0))
          radius (- (/ size 2.0) 1.0)
          base-color 0x99484848
          fill-color 0xFF8FD3FF]
      (doseq [idx (range segments)]
        (let [theta (* (/ (* 2.0 Math/PI) segments) idx)
              px (+ cx (* radius (Math/cos theta)))
              py (+ cy (* radius (Math/sin theta)))
              color (if (< idx filled) fill-color base-color)]
          (.fill graphics (int (Math/floor px)) (int (Math/floor py))
                 (int (Math/ceil (+ px 1.0))) (int (Math/ceil (+ py 1.0)))
                 (unchecked-int color)))))
    nil))

(defn- create-host-screen
  ([title draw-ops-fn click-fn hover-fn close-fn]
   (create-host-screen title draw-ops-fn click-fn hover-fn close-fn nil))
  ([title draw-ops-fn click-fn hover-fn close-fn char-typed-fn]
   (proxy [Screen] [(Component/literal title)]
    (render [^GuiGraphics graphics mouse-x mouse-y _partial-tick]
      (try
        (let [^Screen screen this]
          (.renderBackground screen graphics)
          (when hover-fn
            (hover-fn mouse-x mouse-y))
          (doseq [op (draw-ops-fn mouse-x mouse-y)]
            (render-op! graphics op)))
        (catch Exception e
          (log/error (str "Error rendering hosted screen " title) e))))

    (keyPressed [^long key ^long _scancode ^long _modifiers]
      (cond
        (= key 256)
        (let [^Minecraft mc (Minecraft/getInstance)]
          (.setScreen mc nil)
          true)
        (and char-typed-fn (= key 259))
        (do (char-typed-fn \backspace) true)
        (and char-typed-fn (= key 257))
        (do (char-typed-fn \newline) true)
        :else false))

    (charTyped [ch _modifiers]
      (if char-typed-fn
        (do (char-typed-fn ch) true)
        false))

    (mouseClicked [mouse-x mouse-y _button]
      (try
        (boolean (click-fn mouse-x mouse-y))
        (catch Exception e
          (log/error (str "Error handling hosted screen click " title) e)
          false)))

    (removed []
      (when close-fn
        (close-fn))))))

(defn open-managed-screen!
  "Open a content-owned hosted screen by opaque screen key and payload."
  [screen-key payload]
  (let [result (client-ui/client-open-managed-screen! screen-key payload)]
    (when (= (:command result) :open-screen)
      (let [^Minecraft mc (Minecraft/getInstance)
            title (or (:title result) "Managed Screen")
            char-typed-fn (when (:char-typed? result)
                            (fn [ch]
                              (client-ui/client-handle-managed-screen-char-typed! screen-key ch)))]
        (.setScreen mc
                    (create-host-screen
                      title
                      (fn [mouse-x mouse-y]
                        (client-ui/client-build-managed-screen-draw-ops screen-key mouse-x mouse-y))
                      (fn [mouse-x mouse-y]
                        (client-ui/client-handle-managed-screen-click! screen-key mouse-x mouse-y))
                      (fn [mouse-x mouse-y]
                        (client-ui/client-handle-managed-screen-hover! screen-key mouse-x mouse-y))
                      (fn []
                        (client-ui/client-close-managed-screen! screen-key))
                      char-typed-fn))))))

(defn init! []
  (log/info "Client screen host initialized"))
