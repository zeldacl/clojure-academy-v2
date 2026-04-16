(ns cn.li.forge1201.client.screen-host
  "CLIENT-ONLY generic screen host. AC provides draw ops and interaction handlers."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
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
        (and char-typed-fn (= key 259)) ;; backspace
        (do (char-typed-fn \backspace) true)
        (and char-typed-fn (= key 257)) ;; Enter
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

(defn open-skill-tree-screen!
  ([player-uuid]
   (open-skill-tree-screen! player-uuid nil))
  ([player-uuid learn-context]
   (let [result (ability-runtime/client-open-skill-tree-screen! player-uuid learn-context)]
     (when (= (:command result) :open-screen)
       (let [^Minecraft mc (Minecraft/getInstance)]
         (.setScreen mc
                     (create-host-screen
                       "Skill Tree"
                       (fn [mouse-x mouse-y] (ability-runtime/client-build-skill-tree-draw-ops mouse-x mouse-y))
                       ability-runtime/client-handle-skill-tree-click!
                       ability-runtime/client-handle-skill-tree-hover!
                       ability-runtime/client-close-skill-tree-screen!)))))))

(defn open-preset-editor-screen! [player-uuid]
  (let [result (ability-runtime/client-open-preset-editor-screen! player-uuid)]
    (when (= (:command result) :open-screen)
      (let [^Minecraft mc (Minecraft/getInstance)]
        (.setScreen mc
                    (create-host-screen
                      "Preset Editor"
                      (fn [_ _] (ability-runtime/client-build-preset-editor-draw-ops))
                      ability-runtime/client-handle-preset-editor-click!
                      nil
                      ability-runtime/client-close-preset-editor-screen!))))))

(defn open-location-teleport-screen!
  ([player-uuid]
   (open-location-teleport-screen! player-uuid nil))
  ([player-uuid payload]
   (let [result (ability-runtime/client-open-location-teleport-screen! player-uuid payload)]
     (when (= (:command result) :open-screen)
       (let [^Minecraft mc (Minecraft/getInstance)]
         (.setScreen mc
                     (create-host-screen
                       "Location Teleport"
                       (fn [mouse-x mouse-y]
                         (ability-runtime/client-build-location-teleport-draw-ops mouse-x mouse-y))
                       ability-runtime/client-handle-location-teleport-click!
                       ability-runtime/client-handle-location-teleport-hover!
                       ability-runtime/client-close-location-teleport-screen!
                       ability-runtime/client-handle-location-teleport-char-typed!)))))))

(defn init! []
  (log/info "Client screen host initialized"))