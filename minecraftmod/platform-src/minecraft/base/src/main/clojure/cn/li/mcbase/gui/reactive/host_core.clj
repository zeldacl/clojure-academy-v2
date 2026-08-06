(ns cn.li.mcbase.gui.reactive.host-core
  "Standalone reactive screen host core.

  Version shells supply:
  - :new-screen! (fn [title render-cb key-cb char-cb click-cb removed-cb] -> screen)
  - :render-background! (fn [screen gg mx my pt] ...)
  - :draw-tape! / :render-embedded-runtime!

  Frame order: on-pre-render before layout/tape (terminal fit-scale)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcbase.gui.reactive.clock :as clock]
            [cn.li.mcbase.gui.reactive.input :as input]
            [cn.li.mcbase.gui.reactive.perf :as perf]
            [cn.li.mcbase.gui.reactive.modal :as modal]
            [cn.li.mcbase.gui.reactive.embed :as embed]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcver McAccess]
           [net.minecraft.client Minecraft]))

(defn create-reactive-screen*
  "Build a DelegatingScreen hosting a reactive UiRt."
  ([seams ^UiRt rt title]
   (create-reactive-screen* seams rt title nil))
  ([{:keys [new-screen! render-background! draw-tape! render-embedded-runtime!]}
    ^UiRt rt title
    {:keys [on-close on-pre-render on-post-render on-key-pressed
            on-mouse-released render-background?]
     :as _opts}]
   (let [draw-embeds! (fn [gg left top pt]
                        (embed/render-embedded-runtimes!
                          render-embedded-runtime! rt gg left top pt))]
     (doto (new-screen!
             title
             ;; render
             (fn render-cb [this gg mx my pt]
               (perf/frame-start!)
               (when (not= false render-background?)
                 (render-background! this gg mx my pt))
               (clock/tick! rt pt)
               (rt/resize! rt (double (.-width this)) (double (.-height this)))
               (rt/flush! rt)
               ;; Pre-render before layout so hooks (e.g. terminal fit-scale) can
               ;; adjust root scale / child positions in the same frame's layout.
               (when on-pre-render (on-pre-render gg rt mx my pt))
               (layout/ensure-layout! rt)
               (layout/ensure-tape! rt)
               (draw-tape! gg rt (.-leftOffset this) (.-topOffset this))
               (draw-embeds! gg (.-leftOffset this) (.-topOffset this) pt)
               (when on-post-render (on-post-render gg rt mx my pt))
               (when-let [stats (perf/frame-end!)]
                 (log/info stats)))
             ;; keyPressed
             (fn key-cb [this key-code scan-code modifiers]
               (cond
                 (= (long key-code) 256)
                 (if-let [m (modal/active-modal rt)]
                   (do (modal/modal-key! m key-code scan-code modifiers) true)
                   (do (.onClose this) true))

                 (and on-key-pressed
                      (boolean (on-key-pressed this key-code scan-code modifiers)))
                 true

                 :else
                 (if-let [m (modal/active-modal rt)]
                   (do (modal/modal-key! m key-code scan-code modifiers) true)
                   (input/handle-key-pressed rt key-code scan-code modifiers))))
             ;; charTyped
             (fn char-cb [_this code-point modifiers]
               (if-let [m (modal/active-modal rt)]
                 (do (modal/modal-char! m code-point) true)
                 (input/handle-char-typed rt code-point modifiers)))
             ;; mouseClicked
             (fn click-cb [this mx my button]
               (let [left (.-leftOffset this)
                     top (.-topOffset this)
                     lx (- (double mx) (double left))
                     ly (- (double my) (double top))]
                 (if-let [m (modal/active-modal rt)]
                   (do (modal/modal-mouse-press! m lx ly button) true)
                   (input/handle-mouse-clicked rt left top mx my button))))
             ;; removed
             (fn removed-cb [_this]
               (when on-close (on-close))
               (embed/dispose-embedded-runtimes! rt)
               (input/handle-removed rt)))
       (.withMouseReleased
         (fn release-cb [this mx my button]
           (if (and on-mouse-released
                    (boolean (on-mouse-released this mx my button)))
             true
             (let [left (.-leftOffset this)
                   top (.-topOffset this)
                   lx (- (double mx) (double left))
                   ly (- (double my) (double top))]
               (if-let [m (modal/active-modal rt)]
                 (do (modal/modal-mouse-release! m lx ly button) true)
                 (input/handle-mouse-released rt left top mx my button))))))
       (.withMouseDragged
         (fn drag-cb [this mx my button dx dy]
           (let [left (.-leftOffset this)
                 top (.-topOffset this)
                 lx (- (double mx) (double left))
                 ly (- (double my) (double top))]
             (if-let [m (modal/active-modal rt)]
               (do (modal/modal-mouse-drag! m lx ly button) true)
               (input/handle-mouse-dragged rt left top mx my button dx dy)))))
       (.withMouseMoved
         (fn move-cb [this mx my]
           (when-not (modal/active-modal rt)
             (input/handle-mouse-moved rt (.-leftOffset this) (.-topOffset this) mx my))))
       (.withMouseScrolled
         (fn scroll-cb [this mx my delta]
           (when-not (modal/active-modal rt)
             (input/handle-mouse-scrolled rt (.-leftOffset this) (.-topOffset this) mx my delta))))
       (.withIsPauseScreen (fn [_] false))))))

(defn open-reactive-screen!*
  ([seams ^UiRt rt title]
   (open-reactive-screen!* seams rt title nil))
  ([seams ^UiRt rt title opts]
   (let [^Minecraft mc (Minecraft/getInstance)
         screen (create-reactive-screen* seams rt title opts)]
     (McAccess/setScreen mc screen)
     screen)))
