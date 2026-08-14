(ns cn.li.mc1211.presentation.backend
  "Minecraft 1.21.1 Presentation backend seam.

   Only the version mapping is owned here. Commands and lifecycle remain the
   neutral frame contract supplied by mcmod."
  (:require [cn.li.mcmod.runtime.presentation-backend :as neutral])
  (:import [cn.li.mcmod.runtime PresentationCommand PresentationFrame PresentationPass]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui GuiGraphics Font]))

(def profile :mc-1-21-1)

(defn submit! [backend stage frame-packet & [render-context]]
  ((:submit! backend) stage frame-packet render-context))

(defn reload-resources! [backend generation]
  (neutral/reload-resources! backend generation))

(defn- stage-name [stage]
  (case stage
    :hud "HUD" :hud-underlay "HUD_UNDERLAY" :hud-overlay "HUD_OVERLAY"
    :screen "SCREEN" :world-before-translucent "WORLD_BEFORE_TRANSLUCENT"
    :world-after-translucent "WORLD_AFTER_TRANSLUCENT"
    :first-person "FIRST_PERSON" :post-process "POST_PROCESS" (str stage)))

(defn- callback! [context key values]
  (when (map? context)
    (when-let [f (get context key)]
      (when (fn? f) (apply f values)))))

(defn- draw-command! [^GuiGraphics graphics stage context ^PresentationCommand command]
  (let [[a b c d e f] (vec (.values command))]
    (case (.kind command)
      "quad" (.fill graphics (int a) (int b) (int (+ (double a) (double c)))
                         (int (+ (double b) (double d))) (unchecked-int (int e)))
      "image" (callback! context :draw-image! [graphics stage a b c d e f])
      "glyph-run" (let [^Minecraft mc (Minecraft/getInstance)
                         ^Font font (.-font mc)]
                     (.drawString graphics font (str b) (int c) (int d) (unchecked-int (int e))))
      "push-clip" (.enableScissor graphics (int a) (int b)
                                     (int (+ (double a) (double c)))
                                     (int (+ (double b) (double d))))
      "pop-clip" (.disableScissor graphics)
      "mesh" (callback! context :draw-mesh! [graphics stage a b c d])
      "billboard" (callback! context :draw-billboard! [graphics stage a b c d e f])
      "particle-batch" (callback! context :draw-particle-batch! [graphics stage a b c d e])
      "ribbon" (callback! context :draw-ribbon! [graphics stage a b])
      "beam" (callback! context :draw-beam! [graphics stage a b])
      "item-preview" (callback! context :draw-item-preview! [graphics stage a b c d])
      "camera-contribution" (callback! context :apply-camera! [stage a b c d])
      "post-process" (callback! context :apply-post-process! [graphics stage a b])
      "layer" (callback! context :set-layer! [graphics stage a])
      "order-barrier" (callback! context :order-barrier! [graphics stage])
      nil)))

(defn render! [graphics stage ^PresentationFrame frame]
  (let [context (if (map? graphics) graphics {})
        graphics (if (map? graphics) (:graphics graphics) graphics)]
    (let [wanted (stage-name stage)]
      (doseq [^PresentationPass pass (.passes frame)
              :when (= wanted (.stage pass))
              ^PresentationCommand command (.commands pass)]
        (when (or (instance? GuiGraphics graphics)
                  (and (map? context) (fn? (:draw-mesh! context))))
          (draw-command! graphics stage context command)))))
  frame)

(defn create []
  (neutral/install-renderer! (neutral/create profile) render!))
