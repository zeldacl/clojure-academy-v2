(ns cn.li.fabric1201.gui.screen-impl
  "Fabric 1.20.1 Client-side Screen Implementation

  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register, eliminating hardcoded game concepts.

  Uses shared CGUI runtime host path for :cgui-screen-container payloads."
  (:require [cn.li.mc1201.gui.screen-registry :as screen-registry]
            [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mc1201.gui.cgui-runtime :as cgui-rt]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.gui.screens MenuScreens]
           [cn.li.mc1201.gui CGuiContainerScreen]))

(defn- cgui-screen-container? [m]
  (and (map? m) (= (:type m) :cgui-screen-container)
       (contains? m :cgui) (contains? m :minecraft-container)))

(defn- slots-enabled-for-click?
  [cgui-screen]
  (let [current-atom (:current-tab-atom cgui-screen)
        current-id (when current-atom (deref current-atom))]
    (if current-atom (= "inv" current-id) true)))

(defn- slots-visible?
  [cgui-screen]
  (slots-enabled-for-click? cgui-screen))

(defn- create-cgui-container-screen
  [menu player-inventory title cgui-screen]
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
          (when (or (not= size-dx 0) (not= size-dy 0))
            (let [new-x (int (+ size-dx (.getImageWidthPublic s)))
                  new-y (int (+ size-dy (.getImageHeightPublic s)))]
              (.setImageSize s new-x new-y)))))

      (render [^GuiGraphics gg mouse-x mouse-y partial-ticks]
        (let [^CGuiContainerScreen s this]
          (when root
            (try
              (cgui-rt/resize-root! root (.getXSize s) (.getYSize s))
              (cgui-rt/frame-tick! root {:partial-ticks partial-ticks})
              (catch Exception e
                (log/debug "CGUI frame-tick error:" (.getMessage e)))))
          (if (slots-visible? cgui-screen)
            (.callSuperRender s gg (int mouse-x) (int mouse-y) (float partial-ticks))
            (do
              (.callSuperRenderBackground s gg)
              (reset! left (.getGuiLeft s))
              (reset! top (.getGuiTop s))
              (when root
                (try
                  (cgui-rt/render-tree! gg root @left @top)
                  (catch Exception e
                    (log/debug "CGUI non-slot tab render error:" (.getMessage e)))))
              (.callSuperRenderTooltip s gg (int mouse-x) (int mouse-y))))))

      (renderLabels [^GuiGraphics _gg _mouse-x _mouse-y] (comment "skip labels"))

      (renderBg [^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (let [^CGuiContainerScreen s this]
          (reset! left (.getGuiLeft s))
          (reset! top (.getGuiTop s)))
        (when root
          (try
            (cgui-rt/render-tree! gg root @left @top)
            (catch Exception e
              (log/debug "CGUI renderBg error:" (.getMessage e))))))

      (mouseClicked [mouse-x mouse-y button]
        (when root
          (try
            (cgui-rt/mouse-click! root (int mouse-x) (int mouse-y) @left @top button)
            (catch Exception _ nil)))
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
          (try
            (cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) @left @top)
            (catch Exception _ nil)))
        (let [^CGuiContainerScreen s this]
          (if (slots-enabled-for-click? cgui-screen)
            (.callSuperMouseDragged s mouse-x mouse-y button drag-x drag-y)
            true)))

      (keyPressed [key-code scan-code modifiers]
        (when root
          (try
            (cgui-rt/key-input! root key-code scan-code (char 0))
            (catch Exception _ nil)))
        (let [^CGuiContainerScreen s this]
          (.callSuperKeyPressed s key-code scan-code modifiers)))

      (charTyped [code-point modifiers]
        (when root
          (try
            (cgui-rt/key-input! root 0 0 (char code-point))
            (catch Exception _ nil)))
        (let [^CGuiContainerScreen s this]
          (.callSuperCharTyped s code-point modifiers)))

      (removed []
        (when root
          (try (cgui-rt/dispose! root) (catch Exception _ nil)))))))

(defn- fallback-container-screen
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

(defn- register-one-screen!
  [gui-id menu-type factory-fn-kw]
  (when menu-type
    (MenuScreens/register
      menu-type
      (reify net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor
        (create [_ menu player-inventory title]
          (let [factory-fn (when factory-fn-kw
                             (try
                               (gui/get-screen-factory-fn factory-fn-kw)
                               (catch Exception e
                                 (log/error "[SCREEN-FACTORY] Screen factory not registered for" factory-fn-kw ":" (.getMessage e))
                                 nil)))]
            (if factory-fn
              (try
                (let [screen-data (factory-fn menu player-inventory title)]
                  (if (cgui-screen-container? screen-data)
                    (create-cgui-container-screen menu player-inventory title screen-data)
                    (fallback-container-screen menu player-inventory title)))
                (catch Throwable e
                  (log/error "[SCREEN-FACTORY] Error creating CGui screen for GUI ID" gui-id ":" (.getMessage e))
                  (fallback-container-screen menu player-inventory title)))
              (do
                (log/error "[SCREEN-FACTORY] Missing factory function, using fallback screen. gui-id=" gui-id "factory-fn-kw=" factory-fn-kw)
                (fallback-container-screen menu player-inventory title))))))))
  (log/info "Registered screen factory for GUI ID" gui-id))

(defn register-screens! []
  (log/info "Registering GUI screens for Fabric 1.20.1")
  (try
    (screen-registry/register-all-screens!
      :fabric-1.20.1
      gui/get-all-gui-ids
      gui/get-menu-type
      gui/get-screen-factory-fn-kw
      register-one-screen!)
    (log/info "Screen factories registered successfully (Fabric)")
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

(defn init-client! []
  (log/info "Initializing Fabric 1.20.1 client GUI system")
  (register-screens!)
  (log/info "Fabric 1.20.1 client GUI system initialized"))
