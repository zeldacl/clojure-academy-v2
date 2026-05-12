(ns cn.li.forge1201.gui.screen-impl
  "Forge 1.20.1 Client-side Screen Implementation.

  The proxy remains Forge-specific, but the rendering/input plumbing is split
  into smaller helpers so the lifecycle is easier to follow and test."
  (:require [cn.li.mc1201.gui.screen-registry :as screen-registry]
            [cn.li.mc1201.gui.cgui-runtime :as cgui-rt]
            [cn.li.mcmod.gui.adapter :as gui]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.gui.screens Screen]
           [cn.li.forge1201.shim ForgeClientHelper ForgeClientHelper$ScreenFactory]
           [cn.li.mc1201.gui CGuiContainerScreen]
           [net.minecraftforge.client.event ScreenEvent$BackgroundRendered]
           [net.minecraftforge.common MinecraftForge]))

(def ^:private inv-tab-id "inv")
(def ^:private fallback-bg-color-a (unchecked-int 0xC0101010))
(def ^:private fallback-bg-color-b (unchecked-int 0xD0101010))

(defn- cgui-screen-container?
  [m]
  (and (map? m)
       (= (:type m) :cgui-screen-container)
       (contains? m :cgui)
       (contains? m :minecraft-container)))

(defn- slots-enabled-for-click?
  "True when slot clicks should be forwarded (inv tab only)."
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

(defn- render-root!
  [label root gg left top]
  (when root
    (with-cgui-error label #(cgui-rt/render-tree! gg root left top))))

(defn- dispatch-root-input!
  [label root f]
  (when root
    (with-cgui-error label f)))

(defn- create-cgui-container-screen
  "Build an AbstractContainerScreen proxy that renders and dispatches events to the
  pure Clojure CGUI widget tree."
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
        (let [^CGuiContainerScreen screen this]
          (when (or (not= 0 size-dx) (not= 0 size-dy))
            (.setImageSize screen
                           (int (+ size-dx (.getImageWidthPublic screen)))
                           (int (+ size-dy (.getImageHeightPublic screen)))))))

      (render [^GuiGraphics gg mouse-x mouse-y partial-ticks]
        (let [^CGuiContainerScreen screen this]
          (when root
            (with-cgui-error "CGUI frame-tick error"
              #(do
                 (cgui-rt/resize-root! root (.getXSize screen) (.getYSize screen))
                 (cgui-rt/frame-tick! root {:partial-ticks partial-ticks}))))
          (if (slots-visible? cgui-screen)
            (.callSuperRender screen gg (int mouse-x) (int mouse-y) (float partial-ticks))
            (do
              (.callSuperRenderBackground screen gg)
              (sync-root-bounds! screen left top)
              (render-root! "CGUI non-slot tab render error" root gg @left @top)
              (.callSuperRenderTooltip screen gg (int mouse-x) (int mouse-y))))
          (.post MinecraftForge/EVENT_BUS (ScreenEvent$BackgroundRendered. ^Screen screen gg))))

      (renderLabels [^GuiGraphics _gg _mouse-x _mouse-y]
        (comment "skip labels"))

      (renderBg [^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (let [^CGuiContainerScreen screen this]
          (sync-root-bounds! screen left top))
        (render-root! "CGUI renderBg error" root gg @left @top))

      (mouseClicked [mouse-x mouse-y button]
        (dispatch-root-input!
          "CGUI mouse-click error"
          root
          #(cgui-rt/mouse-click! root (int mouse-x) (int mouse-y) @left @top button))
        (let [^CGuiContainerScreen screen this]
          (if (slots-enabled-for-click? cgui-screen)
            (.callSuperMouseClicked screen mouse-x mouse-y button)
            true)))

      (mouseReleased [mouse-x mouse-y button]
        (let [^CGuiContainerScreen screen this]
          (if (slots-enabled-for-click? cgui-screen)
            (.callSuperMouseReleased screen mouse-x mouse-y button)
            true)))

      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (dispatch-root-input!
          "CGUI mouse-drag error"
          root
          #(cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) @left @top))
        (let [^CGuiContainerScreen screen this]
          (if (slots-enabled-for-click? cgui-screen)
            (.callSuperMouseDragged screen mouse-x mouse-y button drag-x drag-y)
            true)))

      (keyPressed [key-code scan-code modifiers]
        (dispatch-root-input!
          "CGUI key-input error"
          root
          #(cgui-rt/key-input! root key-code scan-code (char 0)))
        (let [^CGuiContainerScreen screen this]
          (.callSuperKeyPressed screen key-code scan-code modifiers)))

      (charTyped [code-point modifiers]
        (dispatch-root-input!
          "CGUI char-input error"
          root
          #(cgui-rt/key-input! root 0 0 (char code-point)))
        (let [^CGuiContainerScreen screen this]
          (.callSuperCharTyped screen code-point modifiers)))

      (removed []
        (when root
          (with-cgui-error "CGUI dispose error"
            #(cgui-rt/dispose! root)))))))

(defn- fallback-container-screen
  "Minimal AbstractContainerScreen that only draws a dark gradient (no CGUI)."
  [menu player-inventory title]
  (proxy [CGuiContainerScreen] [menu player-inventory title]
    (renderBg [^GuiGraphics gg _partial _mx _my]
      (let [^CGuiContainerScreen screen this
            left (.getGuiLeft screen)
            top (.getGuiTop screen)
            right (+ left (.getXSize screen))
            bottom (+ top (.getYSize screen))]
        (.fill gg left top right bottom fallback-bg-color-a)
        (.fill gg left top right bottom fallback-bg-color-b)))))

(defn- resolve-screen-factory
  [factory-fn-kw]
  (when factory-fn-kw
    (try
      (gui/get-screen-factory-fn factory-fn-kw)
      (catch Exception e
        (log/error "[SCREEN-FACTORY] Screen factory not registered for" factory-fn-kw ":" (.getMessage e))
        nil))))

(defn- create-screen-or-fallback
  [gui-id menu player-inventory title factory-fn-kw]
  (let [factory-fn (resolve-screen-factory factory-fn-kw)]
    (if factory-fn
      (try
        (log/info "[SCREEN-FACTORY] Invoking factory-fn")
        (let [screen-data (factory-fn menu player-inventory title)]
          (log/info "[SCREEN-FACTORY] factory-fn returned, type:" (type screen-data) "cgui-screen?" (cgui-screen-container? screen-data))
          (if (cgui-screen-container? screen-data)
            (do
              (log/info "Created CGui screen for GUI ID" gui-id)
              (create-cgui-container-screen menu player-inventory title screen-data))
            (do
              (log/warn "Screen factory did not return :cgui-screen-container for GUI ID" gui-id)
              (fallback-container-screen menu player-inventory title))))
        (catch Throwable e
          (log/error "[SCREEN-FACTORY] Error creating CGui screen for GUI ID" gui-id ":" (.getMessage e))
          (log/error "[SCREEN-FACTORY] Exception:" e)
          (fallback-container-screen menu player-inventory title)))
      (do
        (log/error "[SCREEN-FACTORY] Missing factory function, using fallback screen. gui-id=" gui-id "factory-fn-kw=" factory-fn-kw)
        (fallback-container-screen menu player-inventory title)))))

(defn- register-one-screen!
  [gui-id menu-type factory-fn-kw]
  (log/info "[SCREEN-INIT] Registering GUI ID:" gui-id "menu-type:" menu-type "factory-fn-kw:" factory-fn-kw)
  (when menu-type
    (ForgeClientHelper/registerMenuScreen
     menu-type
     (reify ForgeClientHelper$ScreenFactory
       (create [_ menu player-inventory title]
         (log/info "[SCREEN-FACTORY] Creating screen for GUI ID" gui-id "factory-fn-kw:" factory-fn-kw)
         (create-screen-or-fallback gui-id menu player-inventory title factory-fn-kw)))))
  (log/info "Registered screen for GUI ID" gui-id))

(defn register-screens!
  "Register screen factories with Forge."
  []
  (log/info "Registering GUI screens for Forge 1.20.1")
  (try
    (screen-registry/register-all-screens!
     :forge-1.20.1
     gui/get-all-gui-ids
     gui/get-menu-type
     gui/get-screen-factory-fn-kw
     register-one-screen!)
    (log/info "Screen factories registered successfully")
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

(defn init-client!
  "Initialize client-side GUI system. Call during FMLClientSetupEvent."
  []
  (log/info "Initializing Forge 1.20.1 client GUI system")
  (register-screens!)
  (log/info "Forge 1.20.1 client GUI system initialized"))
