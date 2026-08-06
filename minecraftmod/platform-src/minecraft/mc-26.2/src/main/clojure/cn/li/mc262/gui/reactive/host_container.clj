(ns cn.li.mc262.gui.reactive.host-container
  "Reactive container screen host — wraps DelegatingCGuiContainerScreen with UiRt.

   26.2: graphics handle is GuiGraphicsExtractor; image size is constructor-final;
   draw-tape! submits the reactive render tape through the extractor."
  (:require [cn.li.mcbase.gui.screen.impl :as screen-impl]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc262.gui.reactive.render :as render]
            [cn.li.mc262.gui.reactive.clock :as clock]
            [cn.li.mcbase.gui.reactive.input :as input]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mc262.shim DelegatingCGuiContainerScreen]
           [net.minecraft.client.gui GuiGraphicsExtractor]
           [net.minecraft.world.entity.player Inventory]
           [net.minecraft.world.inventory AbstractContainerMenu]
           [net.minecraft.network.chat Component]))

(defn- slots-active? [screen-data]
  (cond
    (:no-slots? screen-data) false
    (:current-tab-atom screen-data) (= "inv" @(:current-tab-atom screen-data))
    :else true))

(defn- gui-offset [^DelegatingCGuiContainerScreen screen]
  [(.getGuiLeft screen) (.getGuiTop screen)])

(defn- render-embedded-runtimes!
  [^UiRt rt ^GuiGraphicsExtractor gg left top pt]
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

(defn- local-mouse [^DelegatingCGuiContainerScreen screen mx my]
  (let [[left top] (gui-offset screen)]
    [(- (double mx) (double left))
     (- (double my) (double top))]))

(defn- hit-ui? [^UiRt rt ^DelegatingCGuiContainerScreen screen mx my]
  (let [[lx ly] (local-mouse screen mx my)]
    (boolean (layout/hit-test rt lx ly))))

(defn- handle-container-click! [^UiRt rt ^DelegatingCGuiContainerScreen screen mx my button slots-active? super-click!]
  (let [[lx ly] (local-mouse screen mx my)
        hit (layout/hit-test rt lx ly)]
    (when hit (events/dispatch-mouse-press! rt lx ly button))
    (cond
      hit true
      (and slots-active? super-click!) (super-click!)
      :else false)))

(defn create-reactive-container-screen
  "Build a DelegatingCGuiContainerScreen hosting a reactive UiRt.
   Optional :image-width / :image-height in screen-data (26.2 final image size)."
  [screen-data ^AbstractContainerMenu menu ^Inventory player-inv title]
  (let [^UiRt rt (:runtime screen-data)
        slots-active?* (fn [] (slots-active? screen-data))
        iw (or (:image-width screen-data) 176)
        ih (or (:image-height screen-data) 166)
        ^DelegatingCGuiContainerScreen screen
        (DelegatingCGuiContainerScreen. menu player-inv (Component/literal ^String title)
                                        (int iw) (int ih))]
    (doto screen
      (.withRender
        (fn render-cb [^DelegatingCGuiContainerScreen this ^GuiGraphicsExtractor gg mx my pt]
          (try
            (when-let [update-fn (:update-fn screen-data)]
              (update-fn screen-data))
            (clock/tick! rt pt)
            (rt/resize! rt (double (.-width this)) (double (.-height this)))
            (rt/flush! rt)
            (layout/ensure-layout! rt)
            (layout/ensure-tape! rt)
            (if (slots-active?*)
              (.callSuperRender this gg mx my pt)
              (do (.renderBackground this gg (int mx) (int my) (float pt))
                  (render/draw-tape! gg rt (.getGuiLeft this) (.getGuiTop this))
                  (render-embedded-runtimes! rt gg (.getGuiLeft this) (.getGuiTop this) pt)))
            (catch Exception e
              (let [sig [(class e) (.getMessage e)]]
                (when (not= sig (rt/user-signal rt :last-render-error))
                  (rt/put-user-signal! rt :last-render-error sig)
                  (log/stacktrace "host-container render failed (identical repeats suppressed)" e)))))))
      (.withRenderBg
        (fn bg-cb [^DelegatingCGuiContainerScreen this ^GuiGraphicsExtractor gg pt _mx _my]
          (render/draw-tape! gg rt (.getGuiLeft this) (.getGuiTop this))
          (render-embedded-runtimes! rt gg (.getGuiLeft this) (.getGuiTop this) pt)))
      (.withRenderLabels
        (fn labels-cb [^DelegatingCGuiContainerScreen _this ^GuiGraphicsExtractor _gg _mx _my] nil))
      (.withMouseClicked
        (fn click-cb [^DelegatingCGuiContainerScreen this mx my button]
          (if-let [modal (active-modal rt)]
            (let [[lx ly] (local-mouse this mx my)]
              (modal-mouse-press! modal lx ly button)
              true)
            (handle-container-click! rt this mx my button (slots-active?*)
                                     #(boolean (.callSuperMouseClicked this mx my button))))))
      (.withMouseReleased
        (fn release-cb [^DelegatingCGuiContainerScreen this mx my button]
          (if-let [modal (active-modal rt)]
            (let [[lx ly] (local-mouse this mx my)]
              (modal-mouse-release! modal lx ly button)
              true)
            (do
              (let [[lx ly] (local-mouse this mx my)]
                (events/dispatch-mouse-release! rt lx ly button))
              (if (slots-active?*)
                (.callSuperMouseReleased this mx my button)
                true)))))
      (.withMouseDragged
        (fn drag-cb [^DelegatingCGuiContainerScreen this mx my button dx dy]
          (if-let [modal (active-modal rt)]
            (let [[lx ly] (local-mouse this mx my)]
              (modal-mouse-drag! modal lx ly button)
              true)
            (do
              (input/handle-mouse-dragged rt (.getGuiLeft this) (.getGuiTop this) mx my button dx dy)
              (if (and (slots-active?*) (not (hit-ui? rt this mx my)))
                (.callSuperMouseDragged this mx my button dx dy)
                true)))))
      (.withMouseMoved
        (fn move-cb [^DelegatingCGuiContainerScreen this mx my]
          (when-not (active-modal rt)
            (input/handle-mouse-moved rt (.getGuiLeft this) (.getGuiTop this) mx my))))
      (.withMouseScrolled
        (fn scroll-cb [^DelegatingCGuiContainerScreen this mx my delta]
          (when-not (active-modal rt)
            (input/handle-mouse-scrolled rt (.getGuiLeft this) (.getGuiTop this) mx my delta))))
      (.withKeyPressed
        (fn key-cb [^DelegatingCGuiContainerScreen this key-code scan-code modifiers]
          (if-let [modal (active-modal rt)]
            (do (modal-key! modal key-code scan-code modifiers) true)
            (let [handled (input/handle-key-pressed rt key-code scan-code modifiers)]
              (when (and handled (= (long key-code) 256))
                (.onClose this))
              handled))))
      (.withCharTyped
        (fn char-cb [_this code-point modifiers]
          (if-let [modal (active-modal rt)]
            (do (modal-char! modal code-point) true)
            (input/handle-char-typed rt code-point modifiers))))
      (.withRemoved
        (fn removed-cb [_this]
          (when-let [tech (:tech-ui screen-data)]
            (tabbed-gui/detach-tab-sync! tech))
          (dispose-embedded-runtimes! rt)
          (input/handle-removed rt))))))

(defn create-tech-ui-container-screen
  "Create container screen from reactive tech-ui assembled map.
   {:runtime :update-fn :current-tab-atom :tech-ui :minecraft-container :size-dx :size-dy}"
  [screen-data]
  (let [{:keys [runtime minecraft-container size-dx size-dy screen-title player-inventory]} screen-data
        iw (+ 176 (int (or size-dx 0)))
        ih (+ 166 (int (or size-dy 0)))
        screen-data* (assoc screen-data :image-width iw :image-height ih)]
    (create-reactive-container-screen
      screen-data*
      minecraft-container
      player-inventory
      (or screen-title "Container"))))

(screen-impl/install-create-tech-ui-container-screen! create-tech-ui-container-screen)
