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
  "Build a DelegatingScreen hosting a reactive UiRt.
   Optional on-close runs before runtime dispose (screen removed / ESC).
   Supports :on-pre-render and :on-post-render hooks for custom rendering
   (e.g. terminal 3D perspective + cursor overlay)."
  ([^UiRt rt title] (create-reactive-screen rt title nil))
  ([^UiRt rt title {:keys [on-close on-pre-render on-post-render] :as opts}]
  (doto (DelegatingScreen.
          (Component/literal ^String title)
          ;; render
          (fn render-cb [^DelegatingScreen this ^GuiGraphics gg mx my pt]
            (perf/frame-start!)
            (.renderBackground this gg)
            (clock/tick! rt pt)
            (rt/resize! rt (double (.-width this)) (double (.-height this)))
            (rt/flush! rt)
            (layout/ensure-layout! rt)
            (layout/ensure-tape! rt)
            (when on-pre-render (on-pre-render gg rt mx my pt))
            (render/draw-tape! gg rt (.-leftOffset this) (.-topOffset this))
            (when on-post-render (on-post-render gg rt mx my pt))
            (when-let [stats (perf/frame-end!)]
              (log/info stats)))
          ;; keyPressed — ESC always closes regardless of focus state
          (fn key-cb [^net.minecraft.client.gui.screens.Screen this key-code scan-code modifiers]
            (if (= (long key-code) 256)
              (do (.onClose this) true)
              (input/handle-key-pressed rt key-code scan-code modifiers)))
          ;; charTyped
          (fn char-cb [_this code-point modifiers]
            (input/handle-char-typed rt code-point modifiers))
          ;; mouseClicked
          (fn click-cb [^DelegatingScreen this mx my button]
            (input/handle-mouse-clicked rt (.-leftOffset this) (.-topOffset this) mx my button))
          ;; removed
          (fn removed-cb [_this]
            (when on-close (on-close))
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
    (.withIsPauseScreen (fn [_] false)))))

(defn open-reactive-screen!
  "Open a reactive screen on the Minecraft display."
  ([^UiRt rt title] (open-reactive-screen! rt title nil))
  ([^UiRt rt title opts]
  (let [^Minecraft mc (Minecraft/getInstance)
        screen (create-reactive-screen rt title opts)]
    (.setScreen mc screen)
    screen)))
