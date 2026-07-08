(ns cn.li.mc1201.gui.reactive.host
  "Screen hosts for reactive UiRt — standalone + container screen."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mc1201.gui.reactive.render :as render]
            [cn.li.mc1201.gui.reactive.clock :as clock]
            [cn.li.mc1201.gui.reactive.input :as input]
            [cn.li.mc1201.gui.reactive.perf :as perf]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]))

(defn create-reactive-screen
  "Build a DelegatingScreen hosting a reactive UiRt."
  [^UiRt rt title]
  (doto (DelegatingScreen.
          (Component/literal ^String title)
          ;; render
          (fn render-cb [^DelegatingScreen this ^GuiGraphics gg _mx _my pt]
            (perf/frame-start!)
            (.renderBackground this gg)
            (clock/tick! rt pt)
            (rt/resize! rt (double (.-width this)) (double (.-height this)))
            (rt/flush! rt)
            (layout/ensure-layout! rt)
            (layout/ensure-tape! rt)
            (render/draw-tape! gg rt (.-leftOffset this) (.-topOffset this))
            (when-let [stats (perf/frame-end!)]
              (log/info stats)))
          ;; keyPressed
          (fn key-cb [_this key-code scan-code modifiers]
            (input/handle-key-pressed rt key-code scan-code modifiers))
          ;; charTyped
          (fn char-cb [_this code-point modifiers]
            (input/handle-char-typed rt code-point modifiers))
          ;; mouseClicked
          (fn click-cb [^DelegatingScreen this mx my button]
            (input/handle-mouse-clicked rt (.-leftOffset this) (.-topOffset this) mx my button))
          ;; removed
          (fn removed-cb [_this]
            (input/handle-removed rt)))
    (.withMouseReleased
      (fn release-cb [^DelegatingScreen this mx my button]
        (input/handle-mouse-released rt (.-leftOffset this) (.-topOffset this) mx my button)))
    (.withMouseDragged
      (fn drag-cb [^DelegatingScreen this mx my button dx dy]
        (input/handle-mouse-dragged rt (.-leftOffset this) (.-topOffset this) mx my button dx dy)))
    (.withMouseMoved
      (fn move-cb [^DelegatingScreen this mx my]
        (input/handle-mouse-moved rt (.-leftOffset this) (.-topOffset this) mx my)))
    (.withMouseScrolled
      (fn scroll-cb [^DelegatingScreen this mx my delta]
        (input/handle-mouse-scrolled rt (.-leftOffset this) (.-topOffset this) mx my delta)))
    (.withIsPauseScreen (fn [_] false))))

(defn open-reactive-screen!
  "Open a reactive screen on the Minecraft display."
  [^UiRt rt title]
  (let [^Minecraft mc (Minecraft/getInstance)
        screen (create-reactive-screen rt title)]
    (.setScreen mc screen)
    screen))
