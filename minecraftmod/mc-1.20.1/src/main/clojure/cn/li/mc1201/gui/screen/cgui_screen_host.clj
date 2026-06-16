(ns cn.li.mc1201.gui.screen.cgui-screen-host
  "Non-container CGUI screen host — hosts a CGUI widget tree on a plain Screen
  (no Minecraft ContainerMenu needed).

  This mirrors the original AcademyCraft TreeScreen extends CGuiScreen pattern.
  Used by the portable developer and other standalone CGUI screens."
  (:require [cn.li.mc1201.gui.cgui.runtime :as cgui-rt]
            [cn.li.mcmod.hooks.core :as client-ui]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]))

(defn- ^:private cgui-screen-key-pressed
  "Handle key input for a CGUI screen root."
  [root key-code scan-code modifiers cgui-screen-opts]
  ;; Per-screen key hook — matches original AcademyCraft TreeScreen.keyTyped
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
  - session-id: client session ID for dynamic binding
  - opts (optional):
    :key-hook — (fn [key-code scan-code modifiers]) → truthy to consume event
    :on-close — (fn []) called when screen is removed"
  [title root session-id {:keys [key-hook on-close]
                          :or {key-hook nil on-close nil}
                          :as opts}]
  (let [left (atom 0)
        top (atom 0)]
    (proxy [Screen] [(Component/literal title)]
      (render [^GuiGraphics graphics mouse-x mouse-y partial-ticks]
        (try
          (let [^Screen s this
                ^Minecraft mc (Minecraft/getInstance)
                w (.-width s)
                h (.-height s)]
            (.renderBackground s graphics)
            (when root
              (binding [client-ui/*client-session-id* (or session-id "")]
                (cgui-rt/resize-root! root w h)
                (cgui-rt/frame-tick! root {:partial-ticks partial-ticks})
                (cgui-rt/render-tree! graphics root @left @top))))
          (catch Exception e
            (log/error "Error rendering CGUI screen " title e))))

      (keyPressed [^long key-code ^long scan-code ^long modifiers]
        (boolean (cgui-screen-key-pressed root key-code scan-code modifiers opts)))

      (charTyped [code-point modifiers]
        (boolean (cgui-screen-char-typed root code-point modifiers)))

      (mouseClicked [mouse-x mouse-y button]
        (when root
          (binding [client-ui/*client-session-id* (or session-id "")]
            (cgui-rt/mouse-click! root (int mouse-x) (int mouse-y) @left @top button)))
        true)

      (mouseReleased [mouse-x mouse-y button]
        (when root
          (binding [client-ui/*client-session-id* (or session-id "")]
            (cgui-rt/mouse-click! root (int mouse-x) (int mouse-y) @left @top button)))
        true)

      (mouseDragged [mouse-x mouse-y button drag-x drag-y]
        (when root
          (binding [client-ui/*client-session-id* (or session-id "")]
            (cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) @left @top)))
        true)

      (removed []
        (when root
          (binding [client-ui/*client-session-id* (or session-id "")]
            (cgui-rt/dispose! root)))
        (when on-close (on-close)))

      (isPauseScreen []
        false))))

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
