(ns cn.li.mc1201.gui.reactive.host
  "Screen hosts for reactive UiRt — standalone + container screen."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mc1201.gui.reactive.render :as render]
            [cn.li.mc1201.gui.reactive.clock :as clock]
            [cn.li.mcbase.gui.reactive.input :as input]
            [cn.li.mcbase.gui.reactive.perf :as perf]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]))

(defn- render-embedded-runtimes!
  "Render child UiRt instances registered under :embedded-runtimes
   (skill-tree area, cover popups). Same contract as host-container."
  [^UiRt rt ^GuiGraphics gg left top pt]
  (when-let [entries (rt/user-signal rt :embedded-runtimes)]
    (doseq [{:keys [child-rt x y w h visible?-fn anchor-node]} @entries]
      (when (or (nil? visible?-fn) (visible?-fn))
        (let [ax (if anchor-node (.getAbsX ^INode anchor-node) (double x))
              ay (if anchor-node (.getAbsY ^INode anchor-node) (double y))]
          (render/render-embedded-runtime! gg child-rt (+ (double left) ax) (+ (double top) ay) w h pt))))))

(defn- dispose-embedded-runtimes! [^UiRt rt]
  (when-let [entries (rt/user-signal rt :embedded-runtimes)]
    (doseq [{:keys [child-rt]} @entries]
      (rt/dispose! child-rt))))

(defn- active-modal [^UiRt rt]
  (when-let [a (rt/user-signal rt :active-modal)] @a))

(defn- modal-child-local [modal lx ly]
  [(- (double lx) (double (:x modal))) (- (double ly) (double (:y modal)))])

(defn- modal-in-bounds? [modal clx cly]
  (and (>= clx 0.0) (>= cly 0.0) (<= clx (double (:w modal))) (<= cly (double (:h modal)))))

(defn- modal-mouse-press! [modal lx ly button]
  (let [[clx cly] (modal-child-local modal lx ly)]
    (if (modal-in-bounds? modal clx cly)
      (events/dispatch-mouse-press! (:child-rt modal) clx cly button)
      (when-let [f (:on-close-outside modal)] (f)))))

(defn- modal-mouse-release! [modal lx ly button]
  (let [[clx cly] (modal-child-local modal lx ly)]
    (when (modal-in-bounds? modal clx cly)
      (events/dispatch-mouse-release! (:child-rt modal) clx cly button))))

(defn- modal-mouse-drag! [modal lx ly button]
  (let [[clx cly] (modal-child-local modal lx ly)]
    (when (modal-in-bounds? modal clx cly)
      (events/dispatch-mouse-drag! (:child-rt modal) clx cly button))))

(defn- modal-key! [modal key-code scan-code modifiers]
  (if (= (long key-code) 256)
    (when-let [f (:on-close-outside modal)] (f))
    (when-not (events/dispatch-editable-key! (:child-rt modal) key-code (char 0))
      (events/dispatch-key! (:child-rt modal) key-code scan-code modifiers 0))))

(defn- modal-char! [modal code-point]
  (when-not (events/dispatch-editable-key! (:child-rt modal) 0 (char code-point))
    (events/dispatch-char! (:child-rt modal) code-point)))

(defn create-reactive-screen
  "Build a DelegatingScreen hosting a reactive UiRt.
   Optional on-close runs before runtime dispose (screen removed / ESC).
   Supports :on-pre-render and :on-post-render hooks for custom rendering
   (e.g. terminal 3D perspective + cursor overlay), plus optional raw
   key/mouse-release callbacks for interfaces whose interaction is not based
   on ordinary screen-space hit testing."
  ([^UiRt rt title] (create-reactive-screen rt title nil))
  ([^UiRt rt title
    {:keys [on-close on-pre-render on-post-render on-key-pressed
            on-mouse-released render-background?]
     :as opts}]
  (doto (DelegatingScreen.
          (Component/literal ^String title)
          ;; render
          (fn render-cb [^DelegatingScreen this ^GuiGraphics gg mx my pt]
            (perf/frame-start!)
            (when (not= false render-background?)
              (.renderBackground this gg))
            (clock/tick! rt pt)
            (rt/resize! rt (double (.-width this)) (double (.-height this)))
            (rt/flush! rt)
            (layout/ensure-layout! rt)
            (layout/ensure-tape! rt)
            (when on-pre-render (on-pre-render gg rt mx my pt))
            (render/draw-tape! gg rt (.-leftOffset this) (.-topOffset this))
            ;; Skill-tree / overlay embeds (portable developer, skill-tree viewer)
            (render-embedded-runtimes! rt gg (.-leftOffset this) (.-topOffset this) pt)
            (when on-post-render (on-post-render gg rt mx my pt))
            (when-let [stats (perf/frame-end!)]
              (log/info stats)))
          ;; keyPressed — ESC always closes regardless of focus state
          (fn key-cb [^net.minecraft.client.gui.screens.Screen this key-code scan-code modifiers]
            (cond
              (= (long key-code) 256)
              (if-let [modal (active-modal rt)]
                (do (modal-key! modal key-code scan-code modifiers) true)
                (do (.onClose this) true))

              (and on-key-pressed
                   (boolean (on-key-pressed this key-code scan-code modifiers)))
              true

              :else
              (if-let [modal (active-modal rt)]
                (do (modal-key! modal key-code scan-code modifiers) true)
                (input/handle-key-pressed rt key-code scan-code modifiers))))
          ;; charTyped
          (fn char-cb [_this code-point modifiers]
            (if-let [modal (active-modal rt)]
              (do (modal-char! modal code-point) true)
              (input/handle-char-typed rt code-point modifiers)))
          ;; mouseClicked
          (fn click-cb [^DelegatingScreen this mx my button]
            (let [left (.-leftOffset this)
                  top (.-topOffset this)
                  lx (- (double mx) (double left))
                  ly (- (double my) (double top))]
              (if-let [modal (active-modal rt)]
                (do (modal-mouse-press! modal lx ly button) true)
                (input/handle-mouse-clicked rt left top mx my button))))
          ;; removed
          (fn removed-cb [_this]
            (when on-close (on-close))
            (dispose-embedded-runtimes! rt)
            (input/handle-removed rt)))
    (.withMouseReleased
      (fn release-cb [^DelegatingScreen this mx my button]
        (if (and on-mouse-released
                 (boolean (on-mouse-released this mx my button)))
          true
          (let [left (.-leftOffset this)
                top (.-topOffset this)
                lx (- (double mx) (double left))
                ly (- (double my) (double top))]
            (if-let [modal (active-modal rt)]
              (do (modal-mouse-release! modal lx ly button) true)
              (input/handle-mouse-released rt left top mx my button))))))
    (.withMouseDragged
      (fn drag-cb [^DelegatingScreen this mx my button dx dy]
        (let [left (.-leftOffset this)
              top (.-topOffset this)
              lx (- (double mx) (double left))
              ly (- (double my) (double top))]
          (if-let [modal (active-modal rt)]
            (do (modal-mouse-drag! modal lx ly button) true)
            (input/handle-mouse-dragged rt left top mx my button dx dy)))))
    (.withMouseMoved
      (fn move-cb [^DelegatingScreen this mx my]
        (when-not (active-modal rt)
          (input/handle-mouse-moved rt (.-leftOffset this) (.-topOffset this) mx my))))
    (.withMouseScrolled
      (fn scroll-cb [^DelegatingScreen this mx my delta]
        (when-not (active-modal rt)
          (input/handle-mouse-scrolled rt (.-leftOffset this) (.-topOffset this) mx my delta))))
    (.withIsPauseScreen (fn [_] false)))))

(defn open-reactive-screen!
  "Open a reactive screen on the Minecraft display."
  ([^UiRt rt title] (open-reactive-screen! rt title nil))
  ([^UiRt rt title opts]
  (let [^Minecraft mc (Minecraft/getInstance)
        screen (create-reactive-screen rt title opts)]
    (.setScreen mc screen)
    screen)))
