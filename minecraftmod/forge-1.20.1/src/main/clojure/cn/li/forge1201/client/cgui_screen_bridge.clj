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
           [net.minecraft.world.item ItemStack Items Item]
           [net.minecraft.world.level.block Block]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]))

;; -- 3D rotating item preview helpers (Phase 6) --

(defn- resolve-item-stack [^String item-id]
  (try
    (let [parts (str/split item-id #":" 2)
          rl (if (= 2 (count parts))
               (ResourceLocation. (first parts) (second parts))
               (ResourceLocation. item-id))
          ^Item item (.get BuiltInRegistries/ITEM rl)]
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
          ^Block block (.get BuiltInRegistries/BLOCK rl)]
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
      (catch Exception e
        (log/debug "render-3d-preview! failed for" item-id ":" (ex-message e))
        nil))))

(defn- resolve-recipe-kw
  "Map a string recipe-kind to the keyword used by recipe-query/first-recipe-for."
  [recipe-kind-str]
  (case recipe-kind-str
    "ImagFusor"   :imag-fusor
    "MetalFormer" :metal-former
    "Smelting"    :smelting
    :smelting))

(defn- slot-positions
  "Return {:scale :in-x :in-y :out-x :out-y} for a recipe kind.
  Positions from tutorial_windows.xml slot_in/slot_out transforms."
  [recipe-kind-str]
  (case recipe-kind-str
    "ImagFusor"   {:scale 0.6 :in-x 19.0 :in-y 62.5 :out-x 147.0 :out-y 62.5}
    "MetalFormer" {:scale 0.5 :in-x 11.33 :in-y 88.5 :out-x 155.33 :out-y 88.5}
    {:scale 0.6 :in-x 30.0 :in-y 43.17 :out-x 123.33 :out-y 43.17}))

(defn- render-recipe-preview!
  "Render recipe items over the CGUI recipe background.
  Handles Smelting, ImagFusor, and MetalFormer recipe kinds."
  [^GuiGraphics graphics root left-pos top-pos recipe-data _tick]
  (when (and recipe-data (map? recipe-data))
    (try
      (when-let [area (cgui-core/find-widget root "preview-area")]
        (let [{:keys [recipe-kind item-id]} recipe-data
              [ax ay] (cgui-core/get-pos area)
              find-recipe (requiring-resolve 'cn.li.forge1201.integration.recipe-query/first-recipe-for)
              resolve-stack (requiring-resolve 'cn.li.forge1201.integration.recipe-query/item-id->stack)
              rkind (str recipe-kind)
              rkw (resolve-recipe-kw rkind)
              recipe (when find-recipe (find-recipe item-id rkw))]
          (when recipe
            (let [{:keys [scale in-x in-y out-x out-y]} (slot-positions rkind)
                  slot-size (* 32.0 scale)
                  slot-in-cx  (+ left-pos ax (* in-x scale) (/ slot-size 2))
                  slot-in-cy  (+ top-pos ay (* in-y scale) (/ slot-size 2))
                  slot-out-cx (+ left-pos ax (* out-x scale) (/ slot-size 2))
                  slot-out-cy (+ top-pos ay (* out-y scale) (/ slot-size 2))
                  item-scale (/ slot-size 16.0)
                  ps (.pose graphics)
                  input-stack (when-let [input-id (first (:input recipe))]
                                (when resolve-stack (resolve-stack input-id)))
                  output-stack (when-let [output-id (:output recipe)]
                                (when resolve-stack (resolve-stack output-id)))]
              (when input-stack
                (.pushPose ps) (.translate ps (double slot-in-cx) (double slot-in-cy) 110.0)
                (.scale ps (double item-scale) (double item-scale) (double item-scale))
                (.renderFakeItem graphics input-stack -8 -8) (.popPose ps))
              (when output-stack
                (.pushPose ps) (.translate ps (double slot-out-cx) (double slot-out-cy) 110.0)
                (.scale ps (double item-scale) (double item-scale) (double item-scale))
                (.renderFakeItem graphics output-stack -8 -8) (.popPose ps))))))
      (catch Exception e
        (log/debug "render-recipe-preview! failed:" (ex-message e))
        nil))))

(defn- render-crafting-grid-preview!
  "Render crafting table recipe items over the CGUI crafting grid background."
  [^GuiGraphics graphics root left-pos top-pos recipe-data _tick]
  (when (and recipe-data (map? recipe-data))
    (try
      (when-let [area (cgui-core/find-widget root "preview-area")]
        (let [{:keys [item-id]} recipe-data
              find-recipe (requiring-resolve 'cn.li.forge1201.integration.recipe-query/first-recipe-for)
              resolve-stack (requiring-resolve 'cn.li.forge1201.integration.recipe-query/item-id->stack)
              recipe (when find-recipe (find-recipe item-id :crafting))]
          (when recipe
            (let [[ax ay] (cgui-core/get-pos area)
                  grid-offset-x 11.0 grid-offset-y 6.0
                  slot-size 18.0 slot-gap 2.0
                  output-offset-x 94.0 output-offset-y 42.0 output-size 24.0
                  inputs (:input recipe)
                  output-stack (when-let [output-id (:output recipe)]
                                 (when resolve-stack (resolve-stack output-id)))
                  ps (.pose graphics)]
              (doseq [[idx input-id] (map-indexed vector (take 9 inputs))]
                (when (and input-id resolve-stack)
                  (when-let [stack (resolve-stack input-id)]
                    (let [row (quot idx 3) col (rem idx 3)
                          cx (+ left-pos ax grid-offset-x (* col (+ slot-size slot-gap)) (/ slot-size 2))
                          cy (+ top-pos ay grid-offset-y (* row (+ slot-size slot-gap)) (/ slot-size 2))
                          item-scale (/ slot-size 16.0)]
                      (.pushPose ps)
                      (.translate ps (double cx) (double cy) 110.0)
                      (.scale ps (double item-scale) (double item-scale) (double item-scale))
                      (.renderFakeItem graphics stack -8 -8)
                      (.popPose ps)))))
              (when output-stack
                (let [ocx (+ left-pos ax output-offset-x (/ output-size 2))
                      ocy (+ top-pos ay output-offset-y (/ output-size 2))
                      oscale (/ output-size 24.0)]
                  (.pushPose ps)
                  (.translate ps (double ocx) (double ocy) 110.0)
                  (.scale ps (double oscale) (double oscale) (double oscale))
                  (.renderFakeItem graphics output-stack -12 -12)
                  (.popPose ps)))))))
      (catch Exception e
        (log/debug "render-crafting-grid-preview! failed:" (ex-message e))
        nil))))

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
      (catch Exception e
        (log/debug "render-block-preview! failed for" block-id ":" (ex-message e))
        nil))))

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
          ref-width 427.0
          scale (float (/ screen-width ref-width))
          left-pos (int (/ (- screen-width (* gui-width scale)) 2))
          top-pos (int (/ (- screen-height (* gui-height scale)) 2))]
      (cgui-core/set-scale! gui-widget (double scale))
      (reset! left left-pos)
      (reset! top top-pos)
      (cgui-rt/frame-tick! gui-widget {:partial-ticks partial-tick})
      (cgui-rt/render-tree! graphics gui-widget left-pos top-pos)
      (let [tick (swap! tick-counter inc)
            preview-type (some-> preview-type-atom deref)
            preview-data (some-> preview-item-atom deref)]
        (case preview-type
          :block-3d (render-block-preview! graphics gui-widget left-pos top-pos preview-data tick)
          :item-3d  (render-3d-preview! graphics gui-widget left-pos top-pos preview-data tick)
          :recipe         (render-recipe-preview! graphics gui-widget left-pos top-pos preview-data tick)
          :crafting-grid  (render-crafting-grid-preview! graphics gui-widget left-pos top-pos preview-data tick)
          nil)))
    (catch Exception e
      (log/error "Error rendering" log-label ":" (.getMessage e))
      (log/error "[BRIDGE-RENDER-TRACE]" (with-out-str (.printStackTrace e))))))

;; -- Mouse/key handlers --

(defn- mouse-click-cgui!
  [gui-widget left top mouse-x mouse-y button log-label]
  (try
    (cgui-rt/mouse-click! gui-widget (int mouse-x) (int mouse-y) @left @top button)
    true
    (catch Exception e
      (log/error "Error handling" log-label "mouse click:" (.getMessage e))
      (log/error "[BRIDGE-CLICK-TRACE]" (with-out-str (.printStackTrace e)))
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
      (mouseMoved [mouse-x mouse-y]
        ;; Track mouse position relative to CGUI root for hover detection.
        ;; Subtract left/top offsets so frame handlers can compare against
        ;; widget-local coordinates (0,0 = top-left of root widget).
        (swap! (:metadata gui-widget) assoc
               :last-mouse-x (int (- mouse-x @left))
               :last-mouse-y (int (- mouse-y @top)))
        nil)
      (keyPressed [key-code scan-code modifiers]
        ;; ESC key (256) always closes the screen — matches Minecraft GuiScreen
        ;; default behavior and original AcademyCraft CGuiScreen handling.
        (if (= key-code 256)
          (do (.setScreen (Minecraft/getInstance) nil) true)
          (if interactive?
            (key-press-cgui! gui-widget key-code scan-code resolved-log-label)
            false)))
      (mouseScrolled [mouse-x mouse-y scroll-delta]
        (try (cgui-rt/mouse-scroll! gui-widget (int mouse-x) (int mouse-y) @left @top
                                    0.0 (double scroll-delta))
             true
             (catch Exception _ false)))
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
