(ns cn.li.forge1201.client.cgui-screen-bridge
  "CLIENT-ONLY generic CGui screen bridge (Forge layer)."
  (:require [clojure.string :as str]
            [cn.li.mc1201.gui.cgui.runtime :as cgui-rt]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.util.log :as log]
            [cn.li.forge1201.integration.recipe-query :as recipe-query])
  (:import [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]
           [com.mojang.math Axis]
           [net.minecraft.world.item ItemStack Items Item]
           [net.minecraft.world.level.block Block]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [com.mojang.blaze3d.vertex PoseStack]
           [org.lwjgl.opengl GL11]))

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

;; ============================================================================
;; Perspective projection setup — matching upstream showArea FrameEvent
;; ============================================================================

(defn- with-perspective-preview!
  "Set up perspective projection + base modelview matching original showArea
  FrameEvent (GuiTutorial.java:245-298), call (f), then restore state.

  Original projection chain:
    gluPerspective(50, 1, 1f, 100)  ← 50° FOV, square aspect, near=1 far=100
    Translate(position to showArea)  ← centers on preview-area widget
    Scale(366/width * frameScale)     ← screen-size normalization

  Original modelview chain (applied on PoseStack):
    LoadIdentity
    Translate(0, 0, -4)
    Translate(0.55, 0.55, 0.5)
    Scale(0.75, -0.75, 0.75)       ← flip Y for MC coordinate system
    Rotate(-20°, X axis)           ← tilt back

  f receives (^PoseStack ps, ^GuiGraphics graphics) and must push/pop
  its own transforms within the perspective context."
  [^GuiGraphics graphics root left-pos top-pos f]
  (let [^PoseStack ps (.pose graphics)]
      (try
        ;; Flush GUI batch before 3D rendering
        (.endBatch (.bufferSource graphics))
        ;; Push PoseStack and apply base modelview (matching original)
        (.pushPose ps)
        ;; LoadIdentity equivalent: start from current matrix
        ;; Original: Translate(0, 0, -4) * Translate(0.55, 0.55, 0.5)
        (.translate ps 0.55 0.55 -3.5)
        ;; Original: Scale(0.75, -0.75, 0.75) — negative Y flips for MC coords
        (.scale ps (float 0.75) (float -0.75) (float 0.75))
        ;; Original: glRotated(-20, 1, 0, 0.1) ≈ X-axis tilt -20°
        (.mulPose ps (.rotationDegrees Axis/XP (float -20.0)))
        ;; Call the render function
        (f ps graphics)
        ;; Restore after perspective rendering
        (.popPose ps)
        (.endBatch (.bufferSource graphics))
        (catch Exception e
          (log/error "Perspective preview render failed:" (.getMessage e))
          ;; Best-effort restore
          (try (.popPose ps) (catch Exception _))))))

(defn- render-item-3d-preview!
  "Render a 3D item matching upstream drawsItemImpl
  (ViewGroups.java:210-231). Must be called within with-perspective-preview! context.
  Transforms (in order, applied on top of base modelview):
    depthFunc(ALWAYS)              ← no depth test for flat item
    translate(0.54, 0.5, 0)
    scale(-1/16, -1/16, 1)        ← mirror X, tiny scale for GUI
    renderFakeItem(stack, 0, 0)
    depthFunc(LEQUAL)              ← restore"
  [^GuiGraphics graphics ^PoseStack ps item-id _tick]
  (when item-id
    (try
      (let [stack (resolve-item-stack item-id)]
        ;; Original sets glDepthFunc(GL_ALWAYS) — no depth test for flat item in 3D
        (GL11/glDepthFunc GL11/GL_ALWAYS)
        (.pushPose ps)
        ;; Match original drawsItemImpl transform chain
        (.translate ps 0.54 0.5 0.0)
        (.scale ps (float (/ -1.0 16.0)) (float (/ -1.0 16.0)) (float 1.0))
        (.renderFakeItem graphics stack 0 0)
        (.popPose ps)
        ;; Restore depth function
        (GL11/glDepthFunc GL11/GL_LEQUAL))
      (catch Exception e
        (log/debug "render-item-3d-preview! failed for" item-id ":" (ex-message e))
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
              rkind (str recipe-kind)
              rkw (resolve-recipe-kw rkind)
              recipe (recipe-query/first-recipe-for item-id rkw)]
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
                                (recipe-query/item-id->stack input-id))
                  output-stack (when-let [output-id (:output recipe)]
                                (recipe-query/item-id->stack output-id))]
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
              recipe (recipe-query/first-recipe-for item-id :crafting)]
          (when recipe
            (let [[ax ay] (cgui-core/get-pos area)
                  grid-offset-x 11.0 grid-offset-y 6.0
                  slot-size 18.0 slot-gap 2.0
                  output-offset-x 94.0 output-offset-y 42.0 output-size 24.0
                  inputs (:input recipe)
                  output-stack (when-let [output-id (:output recipe)]
                                 (recipe-query/item-id->stack output-id))
                  ps (.pose graphics)]
              (doseq [[idx input-id] (map-indexed vector (take 9 inputs))]
                (when input-id
                  (when-let [stack (recipe-query/item-id->stack input-id)]
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
  "Render a rotating 3D block matching upstream drawsBlockImpl
  (ViewGroups.java:177-207). Must be called within with-perspective-preview! context.
  Transforms (in order, applied on top of base modelview):
    translate(0.15, 0.1, -1)
    rotateY((time/80) % 360)
    scale(0.8, 0.8, 0.8)
    translate(-0.5, -0.5, -0.5)"
  [^GuiGraphics graphics ^PoseStack ps ^String block-id tick]
  (when block-id
    (try
      (when-let [block-state (resolve-block-state block-id)]
        (let [^Minecraft mc (Minecraft/getInstance)
              block-renderer (.getBlockRenderer mc)
              buffer-source (.bufferSource graphics)
              angle (mod (/ (double tick) 80.0) 360.0)]
          (.pushPose ps)
          ;; Match original drawsBlockImpl transform chain
          (.translate ps 0.15 0.1 -1.0)
          (.mulPose ps (.rotationDegrees Axis/YP (float angle)))
          (.scale ps (float 0.8) (float 0.8) (float 0.8))
          (.translate ps -0.5 -0.5 -0.5)
          (.renderSingleBlock block-renderer block-state ps buffer-source
                             15728880  ;; packedLight (fullbright)
                             0)        ;; NO_OVERLAY
          (.popPose ps)))
      (catch Exception e
        (log/debug "render-block-preview! failed for" block-id ":" (ex-message e))
        nil))))

;; -- Screen rendering --

(defn- render-cgui-screen!
  [^Screen screen-this ^GuiGraphics graphics gui-widget left top partial-tick
   log-label tick-counter preview-item-atom preview-type-atom ref-width]
  (try
    (.renderBackground screen-this graphics)
    (let [^Minecraft mc (Minecraft/getInstance)
          window (.getWindow mc)
          screen-width (.getGuiScaledWidth window)
          screen-height (.getGuiScaledHeight window)
          [gui-width gui-height] (cgui-core/get-size gui-widget)
          scale (float (/ screen-width ref-width))
          left-pos (int (/ (- screen-width (* gui-width scale)) 2))
          top-pos (int (/ (- screen-height (* gui-height scale)) 2))]
      (cgui-core/set-scale! gui-widget (double scale))
      (reset! left left-pos)
      (reset! top top-pos)
      ;; Ensure drag metadata atoms exist (lazy init for mouse-drag!)
      (when (nil? (:dragging-node @(:metadata gui-widget)))
        (swap! (:metadata gui-widget) merge
               {:dragging-node (atom nil) :last-drag-time (atom 0) :last-start-time (atom 0)}))
      (cgui-rt/frame-tick! gui-widget {:partial-ticks partial-tick})
      (cgui-rt/render-tree! graphics gui-widget left-pos top-pos)
      (let [tick (swap! tick-counter inc)
            preview-type (some-> preview-type-atom deref)
            preview-data (some-> preview-item-atom deref)]
        (case preview-type
          :block-3d (with-perspective-preview! graphics gui-widget left-pos top-pos
                      (fn [ps g] (render-block-preview! g ps preview-data tick)))
          :item-3d  (with-perspective-preview! graphics gui-widget left-pos top-pos
                      (fn [ps g] (render-item-3d-preview! g ps preview-data tick)))
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
  [gui-widget left top mouse-x mouse-y drag-x drag-y log-label]
  (try
    (cgui-rt/mouse-drag! gui-widget (int mouse-x) (int mouse-y) (int drag-x) (int drag-y) @left @top)
    true
    (catch Exception e
      (log/error "Error handling" log-label "mouse drag:" (.getMessage e))
      false)))

(defn- key-press-cgui!
  "Dispatch a key-press to the CGUI widget tree and return whether it was consumed.
  Keys are only consumed when a focused widget owns keyboard input (editable
  textbox or registered :key handler).  All other keys pass through to vanilla
  so chat (Enter/T), inventory (E), F-keys, movement, and every other vanilla
  interaction continues to work while an interactive CGUI screen is open."
  [gui-widget key-code scan-code log-label]
  (try
    (cgui-rt/key-input! gui-widget key-code scan-code (char 0))
    (boolean (cgui-rt/focused-widget-owns-key? gui-widget))
    (catch Exception e
      (log/error "Error handling" log-label "key press:" (.getMessage e))
      false)))

(defn- char-typed-cgui!
  "Dispatch a typed character to the CGUI widget tree and return whether it was
  consumed.  Same contract as key-press-cgui! — only consumed when a focused
  editable widget actually handles the character."
  [gui-widget code-point log-label]
  (try
    (cgui-rt/key-input! gui-widget 0 0 (char code-point))
    (boolean (cgui-rt/focused-widget-owns-key? gui-widget))
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
  [gui-widget title {:keys [log-label interactive? preview-item-atom preview-type-atom ref-width]
                     :or {ref-width 427.0}}]
  (let [left (atom 0)
        top (atom 0)
        tick-counter (atom 0)
        resolved-log-label (or log-label title)]
    (doto (DelegatingScreen.
            (Component/literal title)
            ;; render
            (fn [^DelegatingScreen this ^GuiGraphics graphics mouse-x mouse-y partial-tick]
              (render-cgui-screen! this graphics gui-widget left top partial-tick
                                   resolved-log-label tick-counter preview-item-atom preview-type-atom ref-width))
            ;; keyPressed
            (fn [_this key-code scan-code modifiers]
              ;; ESC key (256) always closes the screen — matches Minecraft GuiScreen
              ;; default behavior and upstream CGuiScreen handling.
              (if (= key-code 256)
                (do (.setScreen (Minecraft/getInstance) nil) true)
                (if interactive?
                  ;; key-press-cgui! only returns true when a focused editable widget
                  ;; or a widget with :key handlers actually consumed the key.  All
                  ;; other keys (chat, inventory, F-keys, movement, etc.) pass through
                  ;; to vanilla Minecraft.
                  (key-press-cgui! gui-widget key-code scan-code resolved-log-label)
                  false)))
            ;; charTyped
            (fn [_this code-point modifiers]
              (if interactive?
                (char-typed-cgui! gui-widget code-point resolved-log-label)
                false))
            ;; mouseClicked
            (fn [_this mouse-x mouse-y button]
              (mouse-click-cgui! gui-widget left top mouse-x mouse-y button resolved-log-label))
            ;; removed
            (fn [_this]
              (dispose-cgui-screen! gui-widget resolved-log-label)))
      ;; Extra optional Screen methods via with* setters
      (.withMouseDragged
        (fn [_this mouse-x mouse-y button drag-x drag-y]
          (if interactive?
            (mouse-drag-cgui! gui-widget left top mouse-x mouse-y drag-x drag-y resolved-log-label)
            false)))
      (.withMouseMoved
        (fn [_this mouse-x mouse-y]
          ;; Track mouse position relative to CGUI root for hover detection.
          ;; Subtract left/top offsets so frame handlers can compare against
          ;; widget-local coordinates (0,0 = top-left of root widget).
          (swap! (:metadata gui-widget) assoc
                 :last-mouse-x (int (- mouse-x @left))
                 :last-mouse-y (int (- mouse-y @top)))))
      (.withMouseScrolled
        (fn [_this mouse-x mouse-y scroll-delta]
          (try (cgui-rt/mouse-scroll! gui-widget (int mouse-x) (int mouse-y) @left @top
                                      0.0 (double scroll-delta))
               true
               (catch Exception _ false)))))))

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
