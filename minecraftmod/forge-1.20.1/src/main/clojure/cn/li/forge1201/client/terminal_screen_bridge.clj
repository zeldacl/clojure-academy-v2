(ns cn.li.forge1201.client.terminal-screen-bridge
  "CLIENT-ONLY screen bridge for terminal GUI (Forge layer)."
  (:require [cn.li.mcmod.platform.terminal-ui :as terminal-ui]
            [cn.li.forge1201.gui.cgui-runtime :as cgui-rt]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]))


;; ============================================================================
;; Terminal Screen
;; ============================================================================

(defn- create-terminal-screen
  "Create a Minecraft Screen that renders the terminal CGui."
  [player]
  (let [gui-widget (or (terminal-ui/create-terminal-gui player)
                       (cgui/create-widget :size [640 785]))
        left (atom 0)
        top (atom 0)]
    (proxy [Screen] [(Component/literal "Data Terminal")]
      (render [^GuiGraphics graphics mouse-x mouse-y partial-tick]
        (try
          (let [^Screen s this]
            (.renderBackground s graphics))
          (let [^Minecraft mc (Minecraft/getInstance)
                window (.getWindow mc)
                screen-width (.getGuiScaledWidth window)
                screen-height (.getGuiScaledHeight window)
                [gui-width gui-height] (cgui/get-size gui-widget)
                left-pos (int (/ (- screen-width gui-width) 2))
                top-pos (int (/ (- screen-height gui-height) 2))]
            (reset! left left-pos)
            (reset! top top-pos)
            (cgui-rt/frame-tick! gui-widget {:partial-ticks partial-tick})
            (cgui-rt/render-tree! graphics gui-widget left-pos top-pos))
          (catch Exception e
            (log/error "Error rendering terminal screen:" (.getMessage e))
            (log/error "Exception:" e))))

      (mouseClicked [mouse-x mouse-y button]
        (try
          (cgui-rt/mouse-click! gui-widget (int mouse-x) (int mouse-y) @left @top button)
          true
          (catch Exception e
            (log/error "Error handling terminal mouse click:" (.getMessage e))
            false)))

      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (try
          (cgui-rt/mouse-drag! gui-widget (int mouse-x) (int mouse-y) @left @top)
          true
          (catch Exception e
            (log/error "Error handling terminal mouse drag:" (.getMessage e))
            false)))

      (keyPressed [key-code scan-code modifiers]
        (try
          (cgui-rt/key-input! gui-widget key-code scan-code (char 0))
          true
          (catch Exception e
            (log/error "Error handling terminal key press:" (.getMessage e))
            false)))

      (charTyped [code-point modifiers]
        (try
          (cgui-rt/key-input! gui-widget 0 0 (char code-point))
          true
          (catch Exception e
            (log/error "Error handling terminal char typed:" (.getMessage e))
            false)))

      (removed []
        (try
          (cgui-rt/dispose! gui-widget)
          (catch Exception e
            (log/error "Error disposing terminal GUI:" (.getMessage e))))))))

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
          left (atom 0)
          top (atom 0)
          screen (proxy [Screen] [(Component/literal title)]
                   (render [^GuiGraphics graphics mouse-x mouse-y partial-tick]
                     (try
                       (let [^Screen s this]
                         (.renderBackground s graphics))
                       (let [^Minecraft mc (Minecraft/getInstance)
                             window (.getWindow mc)
                             screen-width (.getGuiScaledWidth window)
                             screen-height (.getGuiScaledHeight window)
                             [gui-width gui-height] (cgui/get-size gui-widget)
                             left-pos (int (/ (- screen-width gui-width) 2))
                             top-pos (int (/ (- screen-height gui-height) 2))]
                         (reset! left left-pos)
                         (reset! top top-pos)
                         (cgui-rt/frame-tick! gui-widget {:partial-ticks partial-tick})
                         (cgui-rt/render-tree! graphics gui-widget left-pos top-pos))
                       (catch Exception e
                         (log/error "Error rendering simple GUI:" (.getMessage e)))))

                   (mouseClicked [mouse-x mouse-y button]
                     (try
                       (cgui-rt/mouse-click! gui-widget (int mouse-x) (int mouse-y) @left @top button)
                       true
                       (catch Exception e
                         (log/error "Error handling mouse click:" (.getMessage e))
                         false)))

                   (removed []
                     (try
                       (cgui-rt/dispose! gui-widget)
                       (catch Exception e
                         (log/error "Error disposing GUI:" (.getMessage e))))))]
      (.setScreen mc screen))
    (catch Exception e
      (log/error "Failed to open simple GUI:" (.getMessage e))
      (log/error "Exception:" e))))

(defn init!
  "Initialize terminal screen bridge."
  []
  (log/info "Terminal screen bridge initialized"))
