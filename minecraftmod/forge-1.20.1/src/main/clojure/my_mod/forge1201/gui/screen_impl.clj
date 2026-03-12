(ns my-mod.forge1201.gui.screen-impl
  "Forge 1.20.1 Client-side Screen Implementation

  Platform-agnostic design: Reads GUI metadata and loops through all GUIs
  to register. Uses CGUI runtime to render and handle input for wireless GUIs."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.forge1201.gui.cgui-runtime :as cgui-rt]
            [my-mod.util.log :as log])
  (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.gui.screens MenuScreens]
           [net.minecraft.client.gui.screens.inventory AbstractContainerScreen]))

;; ============================================================================
;; CGUI Screen Proxy
;; ============================================================================

(defn- cgui-screen-container? [m]
  (and (map? m) (= (:type m) :cgui-screen-container)
       (contains? m :cgui) (contains? m :minecraft-container)))

(defn- create-cgui-container-screen
  "Build an AbstractContainerScreen proxy that renders and dispatches events to the
   pure Clojure CGUI widget tree. cgui-screen must be a :cgui-screen-container map
   with :cgui (root widget) and :minecraft-container."
  [menu player-inventory title cgui-screen]
  (when-not (cgui-screen-container? cgui-screen)
    (throw (ex-info "Expected :cgui-screen-container map" {:got (type cgui-screen)})))
  (let [root (:cgui cgui-screen)
        left (atom 0)
        top (atom 0)]
    (proxy [AbstractContainerScreen] [menu player-inventory title]
      (render [^GuiGraphics gg mouse-x mouse-y partial-ticks]
        (when root
          (try
            ;; Use the logical GUI size (container image size) instead of the
            ;; full screen resolution so that XML/TechUI layouts using fixed
            ;; coordinates (e.g. page_wireless.xml) are not stretched.
            (cgui-rt/resize-root! root (.getXSize this) (.getYSize this))
            (cgui-rt/frame-tick! root {:partial-ticks partial-ticks})
            (catch Exception e
              (log/debug "CGUI frame-tick error:" (.getMessage e)))))
        (proxy-super render gg mouse-x mouse-y partial-ticks))

      (renderBg [^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (reset! left (.getGuiLeft this))
        (reset! top (.getGuiTop this))
        (let [left-val @left
              top-val @top
              right (+ left-val (.getXSize this))
              bottom (+ top-val (.getYSize this))]
          (.fill gg left-val top-val right bottom (unchecked-int 0xC0101010))
          (.fill gg left-val top-val right bottom (unchecked-int 0xD0101010)))
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
        (proxy-super mouseClicked mouse-x mouse-y button))

      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (when root
          (try
            (cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) @left @top)
            (catch Exception _ nil)))
        (proxy-super mouseDragged mouse-x mouse-y button drag-x drag-y))

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
  (proxy [AbstractContainerScreen] [menu player-inventory title]
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
