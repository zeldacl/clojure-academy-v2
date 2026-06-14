(ns cn.li.forge1201.client.cgui-screen-bridge
  "CLIENT-ONLY generic CGui screen bridge (Forge layer)."
  (:require [clojure.string :as str]
            [cn.li.mc1201.gui.cgui.runtime :as cgui-rt]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]
           [com.mojang.math Axis]
           [net.minecraft.world.item ItemStack Items]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]))

;; -- 3D rotating item preview helpers (Phase 6) --

(defn- resolve-item-stack [^String item-id]
  (try
    (let [parts (str/split item-id #":" 2)
          rl (if (= 2 (count parts))
               (ResourceLocation. (first parts) (second parts))
               (ResourceLocation. item-id))
          item (.get BuiltInRegistries/ITEM rl)]
      (if (and item (not= item Items/AIR))
        (ItemStack. item)
        (ItemStack. Items/STONE)))
    (catch Exception _ (ItemStack. Items/STONE))))

(defn- resolve-block-state
  "Resolve a block-id string like \"my_mod:constrained_ore\" to a BlockState."
  [^String block-id]
  (try
    (let [parts (str/split block-id #":" 2)
          rl (ResourceLocation. (first parts) (second parts))
          block (.get BuiltInRegistries/BLOCK rl)]
      (when block
        (.defaultBlockState block)))
    (catch Exception _ nil)))

(defn- render-3d-preview!
  [^GuiGraphics graphics root left-pos top-pos item-id tick]
  (when item-id
    (try
      (when-let [area (cgui-core/find-widget root "preview-area")]
        (let [[ax ay] (cgui-core/get-pos area)
              [aw ah] (cgui-core/get-size area)
              stack (resolve-item-stack item-id)
              cx (+ left-pos ax (quot aw 2))
              cy (+ top-pos ay (quot ah 2))
              scale (/ (min aw ah) 32.0)
              angle (mod (* tick 3.0) 360.0)]
          (let [ps (.pose graphics)]
            (.pushPose ps)
            (.translate ps (double cx) (double cy) 100.0)
            (.scale ps (double scale) (double scale) (double scale))
            (.mulPose ps (.rotationDegrees Axis/YP (float angle)))
            (.renderFakeItem graphics stack -8 -8)
            (.popPose ps))))
      (catch Exception _ nil))))

(defn- render-block-preview!
  "Render a rotating 3D block in the preview area.
  Uses BlockRenderer.renderSingleBlock (modern 1.20.1 API) instead of
  the old GL 1.x Tessellator approach from AcademyCraft 1.12."
  [^GuiGraphics graphics root left-pos top-pos ^String block-id tick]
  (when block-id
    (try
      (when-let [block-state (resolve-block-state block-id)]
        (when-let [area (cgui-core/find-widget root "preview-area")]
          (let [[ax ay] (cgui-core/get-pos area)
                [aw ah] (cgui-core/get-size area)
                cx (+ left-pos ax (quot aw 2))
                cy (+ top-pos ay (quot ah 2))
                scale (/ (min aw ah) 35.0)
                angle (mod (* tick 1.5) 360.0)
                ^Minecraft mc (Minecraft/getInstance)
                block-renderer (.getBlockRenderer mc)
                buffer-source (.bufferSource graphics)
                ps (.pose graphics)]
            (.pushPose ps)
            (.translate ps (double cx) (double cy) 120.0)
            (.scale ps (double scale) (double scale) (double scale))
            (.mulPose ps (.rotationDegrees Axis/YP (float angle)))
            (.mulPose ps (.rotationDegrees Axis/XP 30.0))
            (.translate ps -0.5 -0.5 -0.5)
            (.renderSingleBlock block-renderer block-state ps buffer-source
                               15728880  ;; packedLight (fullbright)
                               0)  ;; NO_OVERLAY
            (.popPose ps))))
      (catch Exception _ nil))))

;; -- Screen rendering --

(defn- render-cgui-screen!
  [^Screen screen-this ^GuiGraphics graphics gui-widget left top partial-tick
   log-label tick-counter preview-item-atom preview-type-atom]
  (try
    (.renderBackground screen-this graphics)
    (let [^Minecraft mc (Minecraft/getInstance)
          window (.getWindow mc)
          screen-width (.getGuiScaledWidth window)
          screen-height (.getGuiScaledHeight window)
          [gui-width gui-height] (cgui-core/get-size gui-widget)
          left-pos (int (/ (- screen-width gui-width) 2))
          top-pos (int (/ (- screen-height gui-height) 2))]
      (reset! left left-pos)
      (reset! top top-pos)
      (cgui-rt/frame-tick! gui-widget {:partial-ticks partial-tick})
      (cgui-rt/render-tree! graphics gui-widget left-pos top-pos)
      (let [tick (swap! tick-counter inc)
            preview-type (some-> preview-type-atom deref)]
        ;; Only render 3D for block preview; icon/recipe handled by CGUI
        (when (= preview-type :block-3d)
          (when-let [block-id (some-> preview-item-atom deref)]
            (render-block-preview! graphics gui-widget left-pos top-pos block-id tick)))))
    (catch Exception e
      (log/error "Error rendering" log-label ":" (.getMessage e))
      (log/error "Exception:" e))))

;; -- Mouse/key handlers --

(defn- mouse-click-cgui!
  [gui-widget left top mouse-x mouse-y button log-label]
  (try
    (cgui-rt/mouse-click! gui-widget (int mouse-x) (int mouse-y) @left @top button)
    true
    (catch Exception e
      (log/error "Error handling" log-label "mouse click:" (.getMessage e))
      false)))

(defn- mouse-drag-cgui!
  [gui-widget left top mouse-x mouse-y log-label]
  (try
    (cgui-rt/mouse-drag! gui-widget (int mouse-x) (int mouse-y) @left @top)
    true
    (catch Exception e
      (log/error "Error handling" log-label "mouse drag:" (.getMessage e))
      false)))

(defn- key-press-cgui!
  [gui-widget key-code scan-code log-label]
  (try
    (cgui-rt/key-input! gui-widget key-code scan-code (char 0))
    true
    (catch Exception e
      (log/error "Error handling" log-label "key press:" (.getMessage e))
      false)))

(defn- char-typed-cgui!
  [gui-widget code-point log-label]
  (try
    (cgui-rt/key-input! gui-widget 0 0 (char code-point))
    true
    (catch Exception e
      (log/error "Error handling" log-label "char typed:" (.getMessage e))
      false)))

(defn- dispose-cgui-screen!
  [gui-widget log-label]
  (try
    (cgui-rt/dispose! gui-widget)
    (catch Exception e
      (log/error "Error disposing" log-label ":" (.getMessage e)))))

;; -- Screen construction --

(defn- create-cgui-screen
  [gui-widget title {:keys [log-label interactive? preview-item-atom preview-type-atom]}]
  (let [left (atom 0)
        top (atom 0)
        tick-counter (atom 0)
        resolved-log-label (or log-label title)]
    (proxy [Screen] [(Component/literal title)]
      (render [^GuiGraphics graphics mouse-x mouse-y partial-tick]
        (render-cgui-screen! this graphics gui-widget left top partial-tick
                             resolved-log-label tick-counter preview-item-atom preview-type-atom))
      (mouseClicked [mouse-x mouse-y button]
        (mouse-click-cgui! gui-widget left top mouse-x mouse-y button resolved-log-label))
      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (if interactive?
          (mouse-drag-cgui! gui-widget left top mouse-x mouse-y resolved-log-label)
          false))
      (keyPressed [key-code scan-code modifiers]
        ;; ESC key (256) always closes the screen — matches Minecraft GuiScreen
        ;; default behavior and original AcademyCraft CGuiScreen handling.
        (if (= key-code 256)
          (do (.setScreen (Minecraft/getInstance) nil) true)
          (if interactive?
            (key-press-cgui! gui-widget key-code scan-code resolved-log-label)
            false)))
      (charTyped [code-point modifiers]
        (if interactive?
          (char-typed-cgui! gui-widget code-point resolved-log-label)
          false))
      (removed []
        (dispose-cgui-screen! gui-widget resolved-log-label)))))

;; -- Public API --

(defn open-simple-gui!
  ([gui-widget title] (open-simple-gui! gui-widget title nil))
  ([gui-widget title opts]
   (try
     (log/info "Opening simple GUI screen:" title)
     (let [^Minecraft mc (Minecraft/getInstance)
           screen (create-cgui-screen gui-widget title
                     (merge {:log-label "simple GUI"} opts))]
       (.setScreen mc screen))
     (catch Exception e
       (log/error "Failed to open simple GUI:" (.getMessage e))
       (log/error "Exception:" e)))))

(defn init! []
  (log/info "CGui screen bridge initialized"))
