(ns cn.li.mc1201.gui.reactive.host-container
  "Reactive container screen host — wraps DelegatingCGuiContainerScreen with UiRt.
   Same pattern as host.clj but for container-backed screens."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mc1201.gui.reactive.render :as render]
            [cn.li.mc1201.gui.reactive.clock :as clock]
            [cn.li.mc1201.gui.reactive.input :as input]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mc1201.shim DelegatingCGuiContainerScreen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.world.entity.player Inventory]
           [net.minecraft.world.inventory AbstractContainerMenu]
           [net.minecraft.network.chat Component]))

(defn- slots-active? [screen-data]
  (if-let [current (:current-tab-atom screen-data)]
    (= "inv" @current)
    true))

(defn- gui-offset [^DelegatingCGuiContainerScreen screen]
  [(.-leftPos screen) (.-topPos screen)])

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
   screen-data: {:runtime :update-fn :current-tab-atom :tech-ui ...}
   menu: Minecraft AbstractContainerMenu
   player-inv: Inventory
   title: screen title string"
  [screen-data ^AbstractContainerMenu menu ^Inventory player-inv title]
  (let [^UiRt rt (:runtime screen-data)
        slots-active?* (fn [] (slots-active? screen-data))]
    (doto (DelegatingCGuiContainerScreen. menu player-inv (Component/literal ^String title))
      (.withRender
        (fn render-cb [^DelegatingCGuiContainerScreen this ^GuiGraphics gg mx my pt]
          (try
            (when-let [update-fn (:update-fn screen-data)]
              (update-fn screen-data))
            (.renderBackground this gg)
            (clock/tick! rt pt)
            (rt/resize! rt (double (.-width this)) (double (.-height this)))
            (rt/flush! rt)
            (layout/ensure-layout! rt)
            (layout/ensure-tape! rt)
            (render/draw-tape! gg rt (.-leftPos this) (.-topPos this))
            (when (slots-active?* )
              (.callSuperRender this gg mx my pt))
            (catch Exception e
              (.printStackTrace e)))))
      (.withRenderBg
        (fn bg-cb [^DelegatingCGuiContainerScreen this ^GuiGraphics gg _mx _my _pt]
          (.callSuperRenderBackground this gg)))
      (.withMouseClicked
        (fn click-cb [^DelegatingCGuiContainerScreen this mx my button]
          (handle-container-click! rt this mx my button (slots-active?*)
                                   #(boolean (.callSuperMouseClicked this mx my button)))))
      (.withMouseReleased
        (fn release-cb [^DelegatingCGuiContainerScreen this mx my button]
          (let [[lx ly] (local-mouse this mx my)]
            (events/dispatch-mouse-release! rt lx ly button))
          (if (slots-active?*)
            (.callSuperMouseReleased this mx my button)
            true)))
      (.withMouseDragged
        (fn drag-cb [^DelegatingCGuiContainerScreen this mx my button dx dy]
          (input/handle-mouse-dragged rt (.-leftPos this) (.-topPos this) mx my button dx dy)
          (if (and (slots-active?*) (not (hit-ui? rt this mx my)))
            (.callSuperMouseDragged this mx my button dx dy)
            true)))
      (.withMouseMoved
        (fn move-cb [^DelegatingCGuiContainerScreen this mx my]
          (input/handle-mouse-moved rt (.-leftPos this) (.-topPos this) mx my)))
      (.withMouseScrolled
        (fn scroll-cb [^DelegatingCGuiContainerScreen this mx my delta]
          (input/handle-mouse-scrolled rt (.-leftPos this) (.-topPos this) mx my delta)))
      (.withKeyPressed
        (fn key-cb [_this key-code scan-code modifiers]
          (input/handle-key-pressed rt key-code scan-code modifiers)))
      (.withCharTyped
        (fn char-cb [_this code-point modifiers]
          (input/handle-char-typed rt code-point modifiers)))
      (.withRemoved
        (fn removed-cb [_this]
          (when-let [tech (:tech-ui screen-data)]
            (tabbed-gui/detach-tab-sync! tech))
          (input/handle-removed rt))))))

(defn create-tech-ui-container-screen
  "Create container screen from reactive tech-ui assembled map.
   {:runtime :update-fn :current-tab-atom :tech-ui :minecraft-container :size-dx :size-dy}"
  [screen-data]
  (let [{:keys [runtime minecraft-container size-dx size-dy]} screen-data
        ^DelegatingCGuiContainerScreen screen
        (create-reactive-container-screen
          screen-data
          (.-menu minecraft-container)
          (.-playerInventory minecraft-container)
          (.getTitle minecraft-container))]
    (when size-dx (set! (.-imageWidth screen) (+ 176 (int size-dx))))
    (when size-dy (set! (.-imageHeight screen) (+ 166 (int size-dy))))
    screen))
