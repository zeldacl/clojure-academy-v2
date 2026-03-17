(ns my-mod.forge1201.gui.screen-impl
  "Forge 1.20.1 Client-side Screen Implementation

  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register. Uses CGUI runtime to render and handle input for wireless GUIs.
  Tabbed GUIs: when tab index != 0, slot highlight is not drawn (inv-window only)."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.forge1201.gui.cgui-runtime :as cgui-rt]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.gui.screens MenuScreens]
           [my_mod.forge1201.gui CGuiContainerScreen]
           [net.minecraft.world.inventory Slot ClickType]))

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
      (init []
        (log/info "Initializing CGUI container screen with size delta" size-dx "x" size-dy)
        (log/info "Original size:" (.getImageWidthPublic this) "x" (.getImageHeightPublic this))
        (when (or (not= size-dx 0) (not= size-dy 0))
          (let [new-x (int (+ size-dx (.getImageWidthPublic this)))
                new-y (int (+ size-dy (.getImageHeightPublic this)))]
            (.setImageSize this new-x new-y)))
        (log/info "Adjusted size:" (.getImageWidthPublic this) "x" (.getImageHeightPublic this))
        ;(log/info "Initial left/top:" (.-leftPos ^AbstractContainerScreen this) "/" (.-topPos ^AbstractContainerScreen this))
        ;; (when (or (not= size-dx 0) (not= size-dy 0))
        ;;   (let [new-left (int (/ (- (.width this) (.getXSize this)) 2))
        ;;         new-top (int (/ (- (.height this) (.getYSize this)) 2))]
        ;;     (set! (.-leftPos ^AbstractContainerScreen this) new-left)
        ;;     (set! (.-topPos ^AbstractContainerScreen this) new-top)))
        (proxy-super init) 
            ;(log/info "Post-init left/top:" (.-leftPos ^AbstractContainerScreen this) "/" (.-topPos ^AbstractContainerScreen this))
        )
      
      (render [^GuiGraphics gg mouse-x mouse-y partial-ticks]
        (when root
          (try
            (cgui-rt/resize-root! root (.getXSize this) (.getYSize this))
            (cgui-rt/frame-tick! root {:partial-ticks partial-ticks})
            (catch Exception e
              (log/debug "CGUI frame-tick error:" (.getMessage e)))))
        ;; Non-inv tab: skip full vanilla render so no slots/highlight are drawn; only background + our renderBg (CGui) + tooltip
        (if (slots-visible? cgui-screen)
          (proxy-super render gg mouse-x mouse-y partial-ticks)
          (do
            (.renderBackground this gg)
            (.renderBg this gg (float partial-ticks) (int mouse-x) (int mouse-y))
            (.renderTooltip this gg (int mouse-x) (int mouse-y)))))
      (renderLabels [^GuiGraphics gg mouse-x mouse-y] (comment "skip labels"))
      
      ;; Tabbed GUI: draw slot highlight only when on inv tab (use client tab atom so no delay)
      (renderSlotHighlight [^GuiGraphics gg x y]
        (when (slots-visible? cgui-screen)
          (proxy-super renderSlotHighlight gg x y)))
      
      ;; Tabbed GUI: draw slot (and item) only when on inv tab; on other tabs hide slots and items
      (renderSlot [^GuiGraphics gg ^Slot slot]
        (when (slots-visible? cgui-screen)
          (proxy-super renderSlot gg slot)))
      
      ;; Return null when on non-inv tab so hoveredSlot stays null and no slot is "under" mouse
      (findSlot [x y]
        (if (slots-enabled-for-click? cgui-screen)
          (proxy-super findSlot x y)
          nil))
      
      ;; Block the actual slot-click→packet path when on non-inv tab (even if something calls this directly)
      (slotClicked [^Slot slot slot-id button ^ClickType action-type]
        (when (slots-enabled-for-click? cgui-screen)
          (proxy-super slotClicked slot slot-id button action-type)))
      
      (renderBg [^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (reset! left (.getGuiLeft this))
        (reset! top (.getGuiTop this))
        (let [left-val @left
              top-val @top
              right (+ left-val (.getXSize this))
              bottom (+ top-val (.getYSize this))]
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
        (if (slots-enabled-for-click? cgui-screen)
          (proxy-super mouseClicked mouse-x mouse-y button)
          true))
      
      (mouseReleased [mouse-x mouse-y button]
        (if (slots-enabled-for-click? cgui-screen)
          (proxy-super mouseReleased mouse-x mouse-y button)
          true))
      
      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (when root
          (try
            (cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) @left @top)
            (catch Exception _ nil)))
        (if (slots-enabled-for-click? cgui-screen)
          (proxy-super mouseDragged mouse-x mouse-y button drag-x drag-y)
          true))
      
      (keyPressed [key-code scan-code modifiers]
        (when root
          (try
            (cgui-rt/key-input! root key-code scan-code (char 0))
            (catch Exception _ nil)))
        (proxy-super keyPressed key-code scan-code modifiers))
      
      (charTyped [code-point modifiers]
        (when root
          (try
            (cgui-rt/key-input! root 0 0 (char code-point))
            (catch Exception _ nil)))
        (proxy-super charTyped code-point modifiers))
      
      (onClose []
        (when root
          (try (cgui-rt/dispose! root) (catch Exception _ nil)))
        (proxy-super onClose)))))

(defn- fallback-container-screen
  "Minimal AbstractContainerScreen that only draws a dark gradient (no CGUI)."
  [menu player-inventory title]
  (proxy [CGuiContainerScreen] [menu player-inventory title]
    (renderBg [^GuiGraphics gg _partial _mx _my]
      (let [left (.getGuiLeft this)
            top (.getGuiTop this)
            right (+ left (.getXSize this))
            bottom (+ top (.getYSize this))]
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
