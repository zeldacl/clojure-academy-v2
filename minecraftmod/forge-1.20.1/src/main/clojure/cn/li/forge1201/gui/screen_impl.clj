(ns cn.li.forge1201.gui.screen-impl
  "Forge 1.20.1 Client-side Screen Implementation

  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register. Uses CGUI runtime to render and handle input for wireless GUIs.
  Tabbed GUIs: when tab index != 0, slot highlight is not drawn (inv-window only)."
    (:require [cn.li.mcmod.gui.adapter :as gui]
              [cn.li.forge1201.gui.cgui-runtime :as cgui-rt]
              [cn.li.mcmod.util.log :as log])
    (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.gui.screens Screen]
          [cn.li.forge1201.shim ForgeClientHelper ForgeClientHelper$ScreenFactory]
           [cn.li.forge1201.gui CGuiContainerScreen]
           [net.minecraft.world.inventory Slot ClickType]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.client.event ScreenEvent$BackgroundRendered]))

;; ============================================================================
;; CGUI Screen Proxy
;; ============================================================================

(defn- cgui-screen-container? [m]
  (and (map? m) (= (:type m) :cgui-screen-container)
       (contains? m :cgui) (contains? m :minecraft-container)))

(defn- slots-enabled-for-click?
  "True when slot clicks should be forwarded (inv tab only). Uses :current-tab-atom from tabbed GUIs."
  [cgui-screen]
  (let [current-atom (:current-tab-atom cgui-screen)
        current-id (when current-atom (deref current-atom))
        enabled? (if current-atom (= "inv" current-id) true)]
    ;; (when (and current-atom (not enabled?))
    ;;   (log/debug "slot click blocked: current tab id=" (str current-id)))
    enabled?))

(defn- slots-visible?
  "True when slots (and their items) should be drawn (inv tab only). Same as slots-enabled-for-click? for tabbed GUIs."
  [cgui-screen]
  (slots-enabled-for-click? cgui-screen))

(defn- create-cgui-container-screen
  "Build an AbstractContainerScreen proxy that renders and dispatches events to the
   pure Clojure CGUI widget tree. cgui-screen must be a :cgui-screen-container map
   with :cgui (root widget) and :minecraft-container."
  [menu player-inventory title cgui-screen]
  (when-not (cgui-screen-container? cgui-screen)
    (throw (ex-info "Expected :cgui-screen-container map" {:got (type cgui-screen)})))
  ;; Size delta comes from create-screen (e.g. tech_ui_common tech-ui-size-dx/dy); no business constants here.
  (let [root (:cgui cgui-screen)
        left (atom 0)
        top (atom 0)
        size-dx (int (or (:size-dx cgui-screen) 0))
        size-dy (int (or (:size-dy cgui-screen) 0))]
    (proxy [CGuiContainerScreen] [menu player-inventory title]
      ;; (getXSize []
      ;;   (+ size-dx (proxy-super getXSize)))
      ;; (getYSize []
      ;;   (+ size-dy (proxy-super getYSize)))
      ;; (getGuiLeft []
      ;;   (int (/ (- (.width this) (.getXSize this)) 2)))
      ;; (getGuiTop []
      ;;   (int (/ (- (.height this) (.getYSize this)) 2)))
      
      ;; Slot rendering reads leftPos/topPos directly; set them with type-hinted set! so slots align with the enlarged GUI.
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
          ;; Non-inv tab: skip full vanilla render so no slots/highlight are drawn; only background + our renderBg (CGui) + tooltip
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
              (.callSuperRenderTooltip s gg (int mouse-x) (int mouse-y))))
          ;; JEI 15: GuiEventHandler listens to ScreenEvent.BackgroundRendered to set drawnOnBackground.
          ;; Fire once per frame after our draw path (inv + non-inv); vanilla may also post earlier.
          (.post MinecraftForge/EVENT_BUS (ScreenEvent$BackgroundRendered. ^Screen s gg))))
      (renderLabels [^GuiGraphics gg mouse-x mouse-y] (comment "skip labels"))
      
      (renderBg [^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (let [^CGuiContainerScreen s this]
          (reset! left (.getGuiLeft s))
          (reset! top (.getGuiTop s)))
        (let [left-val @left
              top-val @top
              ^CGuiContainerScreen s this
              right (+ left-val (.getXSize s))
              bottom (+ top-val (.getYSize s))]
                  ;(.fill gg left-val top-val right bottom (unchecked-int 0xC0101010))
                  ;(.fill gg left-val top-val right bottom (unchecked-int 0xD0101010))
          )
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
        ;; Only forward to vanilla (slot handling) when on inv tab; otherwise consume click to block slot interaction
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
          (try (cgui-rt/dispose! root) (catch Exception _ nil)))
        ))))

(defn- fallback-container-screen
  "Minimal AbstractContainerScreen that only draws a dark gradient (no CGUI)."
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

;; ============================================================================
;; Screen Factory Registration (Forge 1.20.1)
;; ============================================================================

(defn register-screens!
  "Register screen factories with Forge.

  For each GUI ID, resolves the screen factory from platform-adapter and
  registers a ScreenConstructor that:
  - Calls the factory to obtain a :cgui-screen-container map
  - If successful, returns a CGUI-backed AbstractContainerScreen
  - Otherwise returns a fallback gradient-only screen."
  []
  (log/info "Registering GUI screens for Forge 1.20.1")
  (try
    (let [platform :forge-1.20.1]
      (doseq [gui-id (gui/get-all-gui-ids)]
        (let [menu-type     (gui/get-menu-type platform gui-id)
              factory-fn-kw (gui/get-screen-factory-fn-kw gui-id)]
          (log/info "[SCREEN-INIT] Registering GUI ID:" gui-id "menu-type:" menu-type "factory-fn-kw:" factory-fn-kw)
          (when menu-type
            (ForgeClientHelper/registerMenuScreen
             menu-type
             (reify ForgeClientHelper$ScreenFactory
               (create [_ menu player-inventory title]
                 (log/info "[SCREEN-FACTORY] Creating screen for GUI ID" gui-id "factory-fn-kw:" factory-fn-kw)
                 (let [factory-fn (when factory-fn-kw
                                    (try
                                      (gui/get-screen-factory-fn factory-fn-kw)
                                      (catch Exception e
                                        (log/error "[SCREEN-FACTORY] Screen factory not registered for" factory-fn-kw ":" (.getMessage e))
                                        nil)))]
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
                       (fallback-container-screen menu player-inventory title))))))))
          (log/info "Registered screen for GUI ID" gui-id))))
    (log/info "Screen factories registered successfully")
    (catch Exception e
      (log/error "Failed to register screen factories:" (.getMessage e))
      (.printStackTrace e))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-client!
  "Initialize client-side GUI system. Call during FMLClientSetupEvent."
  []
  (log/info "Initializing Forge 1.20.1 client GUI system")
  (register-screens!)
  (log/info "Forge 1.20.1 client GUI system initialized"))
