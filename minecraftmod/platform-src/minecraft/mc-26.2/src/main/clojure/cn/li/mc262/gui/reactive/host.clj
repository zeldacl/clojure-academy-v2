(ns cn.li.mc262.gui.reactive.host
  "Screen hosts for reactive UiRt — standalone screen.

   26.2: graphics handle is GuiGraphicsExtractor and draw-tape! submits the
   reactive render tape through that extractor."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mc262.gui.reactive.render :as render]
            [cn.li.mc262.gui.reactive.clock :as clock]
            [cn.li.mcbase.gui.reactive.input :as input]
            [cn.li.mcbase.gui.reactive.perf :as perf]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mc262.shim DelegatingScreen]
           [net.minecraft.client.gui GuiGraphicsExtractor]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]))

(defn create-reactive-screen
  "Build a DelegatingScreen hosting a reactive UiRt."
  ([^UiRt rt title] (create-reactive-screen rt title nil))
  ([^UiRt rt title
    {:keys [on-close on-pre-render on-post-render on-key-pressed
            on-mouse-released render-background?]
     :as opts}]
  (doto (DelegatingScreen.
          (Component/literal ^String title)
          ;; extractRenderState → same (this gg mx my pt) shape as 1.21.1 render
          (fn render-cb [^DelegatingScreen this ^GuiGraphicsExtractor gg mx my pt]
            (perf/frame-start!)
            (when (not= false render-background?)
              (.renderBackground this gg (int mx) (int my) (float pt)))
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
          (fn key-cb [^net.minecraft.client.gui.screens.Screen this key-code scan-code modifiers]
            (cond
              (= (long key-code) 256)
              (do (.onClose this) true)

              (and on-key-pressed
                   (boolean (on-key-pressed this key-code scan-code modifiers)))
              true

              :else
              (input/handle-key-pressed rt key-code scan-code modifiers)))
          (fn char-cb [_this code-point modifiers]
            (input/handle-char-typed rt code-point modifiers))
          (fn click-cb [^DelegatingScreen this mx my button]
            (input/handle-mouse-clicked rt (.-leftOffset this) (.-topOffset this) mx my button))
          (fn removed-cb [_this]
            (when on-close (on-close))
            (input/handle-removed rt)))
    (.withMouseReleased
      (fn release-cb [^DelegatingScreen this mx my button]
        (if (and on-mouse-released
                 (boolean (on-mouse-released this mx my button)))
          true
          (input/handle-mouse-released rt (.-leftOffset this) (.-topOffset this) mx my button))))
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
    (.setScreen (.gui mc) screen)
    screen)))
