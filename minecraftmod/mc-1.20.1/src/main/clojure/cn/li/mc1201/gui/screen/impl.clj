(ns cn.li.mc1201.gui.screen.impl
  "Shared CGUI screen construction and fallback behavior.

  Platform adapters should supply only registration API and optional render-tail
  callbacks (e.g. Forge event bus hooks)."
  (:require [cn.li.mc1201.gui.cgui.runtime :as cgui-rt]
            [cn.li.mcmod.gui.registry :as gui-reg]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui GuiGraphics]
           [cn.li.mc1201.gui CGuiContainerScreen]))

(def ^:private inv-tab-id "inv")

(defn cgui-screen-container?
  [m]
  (and (map? m)
       (= (:type m) :cgui-screen-container)
       (contains? m :cgui)
       (contains? m :minecraft-container)))

(defn- slots-enabled-for-click?
  [cgui-screen]
  (if-let [current-atom (:current-tab-atom cgui-screen)]
    (= inv-tab-id (deref current-atom))
    true))

(defn- slots-visible?
  [cgui-screen]
  (slots-enabled-for-click? cgui-screen))

(defn- with-cgui-error
  [label f]
  (try
    (f)
    (catch Exception e
      (log/debug label (.getMessage e)))))

(defn- sync-root-bounds!
  [^CGuiContainerScreen screen left-atom top-atom]
  (reset! left-atom (.getGuiLeft screen))
  (reset! top-atom (.getGuiTop screen)))

(defn create-cgui-container-screen
  "Build a CGuiContainerScreen proxy using shared widget runtime behavior.

  Optional opts:
  - :on-render-tail! (fn [screen gg mouse-x mouse-y partial-ticks])"
  [menu player-inventory title cgui-screen {:keys [on-render-tail!]}]
  (when-not (cgui-screen-container? cgui-screen)
    (throw (ex-info "Expected :cgui-screen-container map" {:got (type cgui-screen)})))
  (let [root (:cgui cgui-screen)
        left (atom 0)
        top (atom 0)
        size-dx (int (or (:size-dx cgui-screen) 0))
        size-dy (int (or (:size-dy cgui-screen) 0))]
    (proxy [CGuiContainerScreen] [menu player-inventory title]
      (containerTick []
        (let [^CGuiContainerScreen s this]
          (when (or (not= 0 size-dx) (not= 0 size-dy))
            (.setImageSize s
                           (int (+ size-dx (.getImageWidthPublic s)))
                           (int (+ size-dy (.getImageHeightPublic s)))))))

      (render [^GuiGraphics gg mouse-x mouse-y partial-ticks]
        (let [^CGuiContainerScreen s this]
          (when root
            (with-cgui-error "CGUI frame-tick error"
              #(do
                 (cgui-rt/resize-root! root (.getXSize s) (.getYSize s))
                 (cgui-rt/frame-tick! root {:partial-ticks partial-ticks}))))
          (if (slots-visible? cgui-screen)
            (.callSuperRender s gg (int mouse-x) (int mouse-y) (float partial-ticks))
            (do
              (.callSuperRenderBackground s gg)
              (sync-root-bounds! s left top)
              (when root
                (with-cgui-error "CGUI non-slot tab render error"
                  #(cgui-rt/render-tree! gg root @left @top)))
              (.callSuperRenderTooltip s gg (int mouse-x) (int mouse-y))))
          (when on-render-tail!
            (on-render-tail! s gg mouse-x mouse-y partial-ticks))))

      (renderLabels [^GuiGraphics _gg _mouse-x _mouse-y]
        (comment "skip labels"))

      (renderBg [^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (let [^CGuiContainerScreen s this]
          (sync-root-bounds! s left top))
        (when root
          (with-cgui-error "CGUI renderBg error"
            #(cgui-rt/render-tree! gg root @left @top))))

      (mouseClicked [mouse-x mouse-y button]
        (when root
          (with-cgui-error "CGUI mouse-click error"
            #(cgui-rt/mouse-click! root (int mouse-x) (int mouse-y) @left @top button)))
        (let [^CGuiContainerScreen s this]
          (if (slots-enabled-for-click? cgui-screen)
            (.callSuperMouseClicked s mouse-x mouse-y button)
            true)))

      (mouseReleased [mouse-x mouse-y button]
        (let [^CGuiContainerScreen s this]
          (if (slots-enabled-for-click? cgui-screen)
            (.callSuperMouseReleased s mouse-x mouse-y button)
            true)))

      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (when root
          (with-cgui-error "CGUI mouse-drag error"
            #(cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) @left @top)))
        (let [^CGuiContainerScreen s this]
          (if (slots-enabled-for-click? cgui-screen)
            (.callSuperMouseDragged s mouse-x mouse-y button drag-x drag-y)
            true)))

      (keyPressed [key-code scan-code modifiers]
        (let [editing? (and root (cgui-rt/focused-editable-textbox? root))]
          (when root
            (with-cgui-error "CGUI key-input error"
              #(cgui-rt/key-input! root key-code scan-code (char 0))))
          (if editing?
            true
            (let [^CGuiContainerScreen s this]
              (.callSuperKeyPressed s key-code scan-code modifiers)))))

      (charTyped [code-point modifiers]
        (let [editing? (and root (cgui-rt/focused-editable-textbox? root))]
          (when root
            (with-cgui-error "CGUI char-input error"
              #(cgui-rt/key-input! root 0 0 (char code-point))))
          (if editing?
            true
            (let [^CGuiContainerScreen s this]
              (.callSuperCharTyped s code-point modifiers)))))

      (removed []
        (when root
          (with-cgui-error "CGUI dispose error"
            #(cgui-rt/dispose! root)))))))

(defn fallback-container-screen
  [menu player-inventory title]
  (proxy [CGuiContainerScreen] [menu player-inventory title]
    (renderBg [^GuiGraphics gg _partial _mx _my]
      (let [^CGuiContainerScreen s this
            left (.getGuiLeft s)
            top (.getGuiTop s)
            right (+ left (.getXSize s))
            bottom (+ top (.getYSize s))]
        (.fill gg left top right bottom (unchecked-int 0xC0101010))
        (.fill gg left top right bottom (unchecked-int 0xD0101010))))))

(defn create-screen-or-fallback
  [gui-id menu player-inventory title factory-fn-kw {:keys [on-render-tail!]}]
  (let [factory-fn (when factory-fn-kw
                     (try
                       (gui-reg/get-screen-factory-fn factory-fn-kw)
                       (catch Exception e
                         (log/error "[SCREEN-FACTORY] Screen factory not registered for" factory-fn-kw ":" (.getMessage e))
                         nil)))]
    (if factory-fn
      (try
        (let [screen-data (factory-fn menu player-inventory title)]
          (if (cgui-screen-container? screen-data)
            (create-cgui-container-screen menu player-inventory title screen-data {:on-render-tail! on-render-tail!})
            (fallback-container-screen menu player-inventory title)))
        (catch Throwable e
          (log/error "[SCREEN-FACTORY] Error creating CGui screen for GUI ID" gui-id ":" (.getMessage e))
          (fallback-container-screen menu player-inventory title)))
      (do
        (log/error "[SCREEN-FACTORY] Missing factory function, using fallback screen. gui-id=" gui-id "factory-fn-kw=" factory-fn-kw)
        (fallback-container-screen menu player-inventory title)))))