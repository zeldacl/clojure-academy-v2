(ns my-mod.forge1201.gui.screen-impl
  "Forge 1.20.1 Client-side Screen Implementation

  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register. Uses CGUI runtime to render and handle input for wireless GUIs.
  Tabbed GUIs: when tab index != 0, slot highlight is not drawn (inv-window only)."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.forge1201.gui.cgui-runtime :as cgui-rt]
            [my-mod.util.log :as log])
  (:import (net.minecraft.client.gui GuiGraphics)
           (net.minecraft.client.gui.screens MenuScreens)
           (net.minecraft.client.gui.screens.inventory AbstractContainerScreen)
           (my_mod.forge1201.gui CGuiContainerScreen)
           (net.minecraft.world.inventory Slot)
           (net.minecraft.world.inventory ClickType)))

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
  (let [root (:cgui cgui-screen)
        left (atom 0)
        top (atom 0)
        size-dx (int (or (:size-dx cgui-screen) 0))
        size-dy (int (or (:size-dy cgui-screen) 0))]
    (proxy [CGuiContainerScreen] [menu player-inventory title]
      (init []
        (let [^CGuiContainerScreen screen this]
          (when (or (not= size-dx 0) (not= size-dy 0))
            (let [new-x (int (+ size-dx (.getImageWidthPublic screen)))
                  new-y (int (+ size-dy (.getImageHeightPublic screen)))]
              (.setImageSize screen new-x new-y)))
          (.initPublic screen)))

      (render [^GuiGraphics gg mouse-x mouse-y partial-ticks]
        (let [^CGuiContainerScreen screen this]
          (when root
            (try
              (cgui-rt/resize-root! root (.getImageWidthPublic screen) (.getImageHeightPublic screen))
              (cgui-rt/frame-tick! root {:partial-ticks partial-ticks})
              (catch Exception e
                (log/debug "CGUI frame-tick error:" (.getMessage e)))))
          (if (slots-visible? cgui-screen)
            (.renderPublic screen gg (int mouse-x) (int mouse-y) (float partial-ticks))
            (do
              (.renderBackground screen gg)
              (.renderBgPublic screen gg (float partial-ticks) (int mouse-x) (int mouse-y))
              (.renderTooltipPublic screen gg (int mouse-x) (int mouse-y))))))

      (renderLabels [^GuiGraphics gg mouse-x mouse-y] (comment "skip labels"))

      (renderBg [^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (let [^CGuiContainerScreen screen this]
          (reset! left (.getLeftPosPublic screen))
          (reset! top (.getTopPosPublic screen))
          (when root
            (try
              (cgui-rt/render-tree! gg root @left @top)
              (catch Exception e
                (log/debug "CGUI renderBg error:" (.getMessage e)))))))

      (mouseClicked [mouse-x mouse-y button]
        (when root
          (try
            (cgui-rt/mouse-click! root (int mouse-x) (int mouse-y) @left @top (int button))
            (catch Exception _ nil)))
        (if (slots-enabled-for-click? cgui-screen)
          (let [^CGuiContainerScreen screen this]
            (.mouseClickedPublic screen (double mouse-x) (double mouse-y) (int button)))
          true))

      (mouseReleased [mouse-x mouse-y button]
        (if (slots-enabled-for-click? cgui-screen)
          (let [^CGuiContainerScreen screen this]
            (.mouseReleasedPublic screen (double mouse-x) (double mouse-y) (int button)))
          true))

      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (when root
          (try
            (cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) @left @top)
            (catch Exception _ nil)))
        (if (slots-enabled-for-click? cgui-screen)
          (let [^CGuiContainerScreen screen this]
            (.mouseDraggedPublic screen (double mouse-x) (double mouse-y) (int button) (double drag-x) (double drag-y)))
          true))

      (keyPressed [key-code scan-code modifiers]
        (when root
          (try
            (cgui-rt/key-input! root (int key-code) (int scan-code) (char 0))
            (catch Exception _ nil)))
        (let [^CGuiContainerScreen screen this]
          (.keyPressedPublic screen (int key-code) (int scan-code) (int modifiers))))

      (charTyped [code-point modifiers]
        (when root
          (try
            (cgui-rt/key-input! root 0 0 code-point)
            (catch Exception _ nil)))
        (let [^CGuiContainerScreen screen this]
          (.charTypedPublic screen code-point (int modifiers))))

      (onClose []
        (when root
          (try (cgui-rt/dispose! root) (catch Exception _ nil)))
        (let [^CGuiContainerScreen screen this]
          (.onClosePublic screen))))))

(defn- fallback-container-screen
  "Minimal AbstractContainerScreen that only draws a dark gradient (no CGUI)."
  [menu player-inventory title]
  (proxy [CGuiContainerScreen] [menu player-inventory title]
    (renderBg [^GuiGraphics gg _partial _mx _my]
      (let [^CGuiContainerScreen screen this
            left (.getLeftPosPublic screen)
            top (.getTopPosPublic screen)
            right (+ left (.getImageWidthPublic screen))
            bottom (+ top (.getImageHeightPublic screen))]
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
              factory-fn-kw (gui/get-screen-factory-fn-kw gui-id)
              factory-fn    (when factory-fn-kw
                              (ns-resolve 'my-mod.gui.platform-adapter
                                          (symbol (name factory-fn-kw))))]
          (when menu-type
            (MenuScreens/register
             menu-type
             (reify net.minecraft.client.gui.screens.MenuScreens$ScreenConstructor
               (create [_ menu player-inventory title]
                 (if factory-fn
                   (try
                     (let [screen-data (factory-fn menu player-inventory title)]
                       (if (cgui-screen-container? screen-data)
                         (do
                           (log/info "Created CGui screen for GUI ID" gui-id)
                           (create-cgui-container-screen menu player-inventory title screen-data))
                         (do
                           (log/warn "Screen factory did not return :cgui-screen-container for GUI ID" gui-id)
                           (fallback-container-screen menu player-inventory title))))
                     (catch Exception e
                       (log/error "Error creating CGui screen for GUI ID" gui-id ":" (.getMessage e))
                       (fallback-container-screen menu player-inventory title)))
                   (fallback-container-screen menu player-inventory title))))))
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
