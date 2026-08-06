(ns cn.li.mcbase.gui.reactive.host-container-core
  "Reactive container screen host core.

  Version shells supply:
  - :new-screen! (fn [menu inv title iw ih] -> DelegatingCGuiContainerScreen)
  - :render-background! (fn [screen gg mx my pt] ...)
  - :draw-tape! / :render-embedded-runtime!
  - :apply-image-size! (fn [screen iw ih] ...) optional; Loom setImageSize path"
  (:require [cn.li.mcbase.gui.screen.impl :as screen-impl]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcbase.gui.reactive.clock :as clock]
            [cn.li.mcbase.gui.reactive.input :as input]
            [cn.li.mcbase.gui.reactive.modal :as modal]
            [cn.li.mcbase.gui.reactive.embed :as embed]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]))

(defn- slots-active?
  [screen-data]
  (cond
    (:no-slots? screen-data) false
    (:current-tab-atom screen-data) (= "inv" @(:current-tab-atom screen-data))
    :else true))

(defn- gui-offset
  [screen]
  [(.getGuiLeft screen) (.getGuiTop screen)])

(defn- local-mouse
  [screen mx my]
  (let [[left top] (gui-offset screen)]
    [(- (double mx) (double left))
     (- (double my) (double top))]))

(defn- hit-ui?
  [^UiRt rt screen mx my]
  (let [[lx ly] (local-mouse screen mx my)]
    (boolean (layout/hit-test rt lx ly))))

(defn- handle-container-click!
  [^UiRt rt screen mx my button slots-active? super-click!]
  (let [[lx ly] (local-mouse screen mx my)
        hit (layout/hit-test rt lx ly)]
    (when hit (events/dispatch-mouse-press! rt lx ly button))
    (cond
      hit true
      (and slots-active? super-click!) (super-click!)
      :else false)))

(defn create-reactive-container-screen*
  "Build a container screen hosting a reactive UiRt."
  [{:keys [new-screen! render-background! draw-tape! render-embedded-runtime!]}
   screen-data menu player-inv title]
  (let [^UiRt rt (:runtime screen-data)
        slots-active?* (fn [] (slots-active? screen-data))
        iw (or (:image-width screen-data) screen-impl/default-image-width)
        ih (or (:image-height screen-data) screen-impl/default-image-height)
        screen (new-screen! menu player-inv title (int iw) (int ih))
        draw-embeds! (fn [gg left top pt]
                       (embed/render-embedded-runtimes!
                         render-embedded-runtime! rt gg left top pt))]
    (doto screen
      (.withRender
        (fn render-cb [this gg mx my pt]
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
              (do (render-background! this gg mx my pt)
                  (draw-tape! gg rt (.getGuiLeft this) (.getGuiTop this))
                  (draw-embeds! gg (.getGuiLeft this) (.getGuiTop this) pt)))
            (catch Exception e
              (let [sig [(class e) (.getMessage e)]]
                (when (not= sig (rt/user-signal rt :last-render-error))
                  (rt/put-user-signal! rt :last-render-error sig)
                  (log/stacktrace "host-container render failed (identical repeats suppressed)" e)))))))
      (.withRenderBg
        (fn bg-cb [this gg pt _mx _my]
          (draw-tape! gg rt (.getGuiLeft this) (.getGuiTop this))
          (draw-embeds! gg (.getGuiLeft this) (.getGuiTop this) pt)))
      (.withRenderLabels
        (fn labels-cb [_this _gg _mx _my] nil))
      (.withMouseClicked
        (fn click-cb [this mx my button]
          (if-let [m (modal/active-modal rt)]
            (let [[lx ly] (local-mouse this mx my)]
              (modal/modal-mouse-press! m lx ly button)
              true)
            (handle-container-click! rt this mx my button (slots-active?*)
                                     #(boolean (.callSuperMouseClicked this mx my button))))))
      (.withMouseReleased
        (fn release-cb [this mx my button]
          (if-let [m (modal/active-modal rt)]
            (let [[lx ly] (local-mouse this mx my)]
              (modal/modal-mouse-release! m lx ly button)
              true)
            (do
              (let [[lx ly] (local-mouse this mx my)]
                (events/dispatch-mouse-release! rt lx ly button))
              (if (slots-active?*)
                (.callSuperMouseReleased this mx my button)
                true)))))
      (.withMouseDragged
        (fn drag-cb [this mx my button dx dy]
          (if-let [m (modal/active-modal rt)]
            (let [[lx ly] (local-mouse this mx my)]
              (modal/modal-mouse-drag! m lx ly button)
              true)
            (do
              (input/handle-mouse-dragged rt (.getGuiLeft this) (.getGuiTop this) mx my button dx dy)
              (if (and (slots-active?*) (not (hit-ui? rt this mx my)))
                (.callSuperMouseDragged this mx my button dx dy)
                true)))))
      (.withMouseMoved
        (fn move-cb [this mx my]
          (when-not (modal/active-modal rt)
            (input/handle-mouse-moved rt (.getGuiLeft this) (.getGuiTop this) mx my))))
      (.withMouseScrolled
        (fn scroll-cb [this mx my delta]
          (when-not (modal/active-modal rt)
            (input/handle-mouse-scrolled rt (.getGuiLeft this) (.getGuiTop this) mx my delta))))
      (.withKeyPressed
        (fn key-cb [this key-code scan-code modifiers]
          (if-let [m (modal/active-modal rt)]
            (do (modal/modal-key! m key-code scan-code modifiers) true)
            (let [handled (input/handle-key-pressed rt key-code scan-code modifiers)]
              (when (and handled (= (long key-code) 256))
                (.onClose this))
              handled))))
      (.withCharTyped
        (fn char-cb [_this code-point modifiers]
          (if-let [m (modal/active-modal rt)]
            (do (modal/modal-char! m code-point) true)
            (input/handle-char-typed rt code-point modifiers))))
      (.withRemoved
        (fn removed-cb [_this]
          (when-let [tech (:tech-ui screen-data)]
            (tabbed-gui/detach-tab-sync! tech))
          (embed/dispose-embedded-runtimes! rt)
          (input/handle-removed rt))))))

(defn create-tech-ui-container-screen*
  "Create container screen from reactive tech-ui assembled map."
  [{:keys [apply-image-size!] :as seams} screen-data]
  (let [{:keys [minecraft-container screen-title player-inventory]} screen-data
        size (screen-impl/resolve-image-size screen-data)
        screen-data* (if size
                       (assoc screen-data
                              :image-width (nth size 0)
                              :image-height (nth size 1))
                       screen-data)
        screen (create-reactive-container-screen*
                 seams
                 screen-data*
                 minecraft-container
                 player-inventory
                 (or screen-title "Container"))]
    (when (and size apply-image-size!)
      (apply-image-size! screen (nth size 0) (nth size 1)))
    screen))

(defn install!
  [seams]
  (screen-impl/install-create-tech-ui-container-screen!
    (fn [screen-data]
      (create-tech-ui-container-screen* seams screen-data))))
