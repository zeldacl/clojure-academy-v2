(ns cn.li.mc1201.gui.screen.cgui-screen-host
  "Non-container CGUI screen host — hosts a CGUI widget tree on a plain Screen
  (no Minecraft ContainerMenu needed).

  This mirrors the upstream TreeScreen extends CGuiScreen pattern.
  Used by the portable developer and other standalone CGUI screens."
  (:require [cn.li.mc1201.gui.cgui.runtime :as cgui-rt]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]))

(defn- ^:private cgui-screen-key-pressed
  "Handle key input for a CGUI screen root."
  [root key-code scan-code modifiers cgui-screen-opts]
  ;; Per-screen key hook — matches upstream TreeScreen.keyTyped
  (or (when-let [hook (:key-hook cgui-screen-opts)]
        (hook key-code scan-code modifiers))
      (let [owns-key? (and root (cgui-rt/focused-widget-owns-key? root))]
        (when root
          (cgui-rt/key-input! root key-code scan-code (char 0)))
        (if owns-key?
          true
          (= key-code 256)))))  ;; ESC → close

(defn- ^:private cgui-screen-char-typed
  [root code-point modifiers]
  (let [owns-key? (and root (cgui-rt/focused-widget-owns-key? root))]
    (when root
      (cgui-rt/key-input! root 0 0 (char code-point)))
    owns-key?))

(defn create-cgui-screen
  "Build a proxy Screen that hosts a CGUI widget tree.

  Parameters:
  - title: screen title string
  - root: CGUI root widget (container)
  - session-id: client session ID (stored in DelegatingScreen, auto-managed per callback)
  - opts (optional):
    :key-hook — (fn [key-code scan-code modifiers]) → truthy to consume event
    :on-close — (fn []) called when screen is removed"
  [title root session-id {:keys [key-hook on-close]
                          :or {key-hook nil on-close nil}
                          :as opts}]
  (let [sid (or session-id "")]
    (doto (DelegatingScreen.
            (Component/literal title)
            ;; render — context auto-managed by DelegatingScreen
            (fn [^DelegatingScreen this ^GuiGraphics graphics mouse-x mouse-y partial-ticks]
              (try
                (let [w (.-width this)
                      h (.-height this)]
                  (.renderBackground this graphics)
                  (when root
                    (cgui-rt/resize-root! root w h)
                    ;; Apply root CENTER/CENTER alignment — matching upstream
                    ;; CGuiScreen (full-screen overlay) behavior
                    ;; where LambdaLib2 centered the root widget on screen.
                    (let [tm (get @(:metadata root) :transform-meta {})
                          align-w (:align-width tm)
                          align-h (:align-height tm)
                          [rw rh] (cgui-core/get-size root)]
                      (when (= align-w :center)
                        (set! (.-leftOffset this) (long (/ (- (double w) (double rw)) 2.0))))
                      (when (= align-h :center)
                        (set! (.-topOffset this) (long (/ (- (double h) (double rh)) 2.0)))))
                    (swap! (:metadata root) assoc
                           :last-mouse-x (int (- mouse-x (.-leftOffset this)))
                           :last-mouse-y (int (- mouse-y (.-topOffset this))))
                    (cgui-rt/frame-tick! root {:partial-ticks partial-ticks})
                    (cgui-rt/render-tree! graphics root (.-leftOffset this) (.-topOffset this))))
                (catch Exception e
                  (log/error "Error rendering CGUI screen " title e))))
            ;; keyPressed — no context needed (key-hook doesn't use session)
            (fn [_this ^long key-code ^long scan-code ^long modifiers]
              (boolean (cgui-screen-key-pressed root key-code scan-code modifiers opts)))
            ;; charTyped — no context needed
            (fn [_this code-point modifiers]
              (boolean (cgui-screen-char-typed root code-point modifiers)))
            ;; mouseClicked — context auto-managed by DelegatingScreen
            (fn [^DelegatingScreen this mouse-x mouse-y button]
              (when root
                (cgui-rt/mouse-click! root (int mouse-x) (int mouse-y)
                                      (.-leftOffset this) (.-topOffset this) button))
              true)
            ;; removed — context auto-managed by DelegatingScreen
            (fn [_this]
              (when root
                (cgui-rt/dispose! root))
              (when on-close (on-close))))
      ;; Extra Screen methods via with* setters
      (.withClientSession sid)
      (.withMouseReleased
        (fn [^DelegatingScreen this mouse-x mouse-y button]
          (when root
            (cgui-rt/mouse-click! root (int mouse-x) (int mouse-y)
                                  (.-leftOffset this) (.-topOffset this) button))
          true))
      (.withMouseDragged
        (fn [^DelegatingScreen this mouse-x mouse-y button drag-x drag-y]
          (when root
            (cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y)
                                 (int drag-x) (int drag-y)
                                 (.-leftOffset this) (.-topOffset this)))
          true))
      (.withMouseMoved
        (fn [^DelegatingScreen this mouse-x mouse-y]
          (when root
            (swap! (:metadata root) assoc
                   :last-mouse-x (int (- mouse-x (.-leftOffset this)))
                   :last-mouse-y (int (- mouse-y (.-topOffset this)))))
          false))
      (.withIsPauseScreen
        (fn [_this] false)))))

(defn open-cgui-screen!
  "Open a CGUI screen on the Minecraft display.
  root — CGUI root widget
  session-id — client session ID
  opts — same as create-cgui-screen"
  [root session-id & [opts]]
  (let [^Minecraft mc (Minecraft/getInstance)
        title (or (:title opts) "CGUI Screen")
        screen (create-cgui-screen title root session-id opts)]
    (.setScreen mc screen)
    screen))
