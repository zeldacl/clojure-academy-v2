(ns cn.li.forge1201.client.terminal-screen-bridge
  "CLIENT-ONLY screen bridge for terminal GUI (Forge layer)."
  (:require [cn.li.mcmod.platform.terminal-ui :as terminal-ui]
            [cn.li.mc1201.gui.cgui.runtime :as cgui-rt]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]))


;; ============================================================================
;; Simple CGui Screen Host
;; ============================================================================

(defn- render-cgui-screen!
  [^Screen screen-this ^GuiGraphics graphics gui-widget left top partial-tick log-label]
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
      (cgui-rt/render-tree! graphics gui-widget left-pos top-pos))
    (catch Exception e
      (log/error "Error rendering" log-label ":" (.getMessage e))
      (log/error "Exception:" e))))

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

(defn- create-cgui-screen
  "Create a Minecraft Screen host for a simple CGui widget tree."
  [gui-widget title {:keys [log-label]}]
  (let [left (atom 0)
        top (atom 0)
        resolved-log-label (or log-label title)]
    (proxy [Screen] [(Component/literal title)]
      (render [^GuiGraphics graphics mouse-x mouse-y partial-tick]
        (render-cgui-screen! this graphics gui-widget left top partial-tick resolved-log-label))

      (mouseClicked [mouse-x mouse-y button]
        (mouse-click-cgui! gui-widget left top mouse-x mouse-y button resolved-log-label))

      (removed []
        (dispose-cgui-screen! gui-widget resolved-log-label)))))

(defn- create-interactive-cgui-screen
  "Create a Minecraft Screen host for CGui trees that need drag and keyboard input."
  [gui-widget title {:keys [log-label]}]
  (let [left (atom 0)
        top (atom 0)
        resolved-log-label (or log-label title)]
    (proxy [Screen] [(Component/literal title)]
      (render [^GuiGraphics graphics mouse-x mouse-y partial-tick]
        (render-cgui-screen! this graphics gui-widget left top partial-tick resolved-log-label))

      (mouseClicked [mouse-x mouse-y button]
        (mouse-click-cgui! gui-widget left top mouse-x mouse-y button resolved-log-label))

      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (mouse-drag-cgui! gui-widget left top mouse-x mouse-y resolved-log-label))

      (keyPressed [key-code scan-code modifiers]
        (key-press-cgui! gui-widget key-code scan-code resolved-log-label))

      (charTyped [code-point modifiers]
        (char-typed-cgui! gui-widget code-point resolved-log-label))

      (removed []
        (dispose-cgui-screen! gui-widget resolved-log-label)))))

;; ============================================================================
;; Terminal Screen
;; ============================================================================

(defn- create-terminal-screen
  "Create a Minecraft Screen that renders the terminal CGui."
  [player]
  (create-interactive-cgui-screen
   (or (terminal-ui/create-terminal-gui player)
       (cgui-core/create-widget :size [640 785]))
   "Data Terminal"
   {:log-label "terminal screen"}))

;; ============================================================================
;; Screen Opening Functions
;; ============================================================================

(defn open-terminal-screen!
  "Open terminal screen. Called by AC layer via item right-click."
  [player]
  (try
    (log/info "Opening terminal screen for player:" player)
    (let [^Minecraft mc (Minecraft/getInstance)]
      (.setScreen mc (create-terminal-screen player)))
    (catch Exception e
      (log/error "Failed to open terminal screen:" (.getMessage e))
      (log/error "Exception:" e))))

(defn open-simple-gui!
  "Open a simple CGui screen (for apps like About, Tutorial, etc).

  Args:
  - gui-widget: CGui widget tree
  - title: Screen title string"
  [gui-widget title]
  (try
    (log/info "Opening simple GUI screen:" title)
    (let [^Minecraft mc (Minecraft/getInstance)
          screen (create-cgui-screen gui-widget title {:log-label "simple GUI"})]
      (.setScreen mc screen))
    (catch Exception e
      (log/error "Failed to open simple GUI:" (.getMessage e))
      (log/error "Exception:" e))))

(defn init!
  "Initialize terminal screen bridge."
  []
  (log/info "Terminal screen bridge initialized"))
