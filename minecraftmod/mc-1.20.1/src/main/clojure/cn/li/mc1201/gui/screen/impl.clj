(ns cn.li.mc1201.gui.screen.impl
  "Shared CGUI screen construction and fallback behavior.

  Platform adapters should supply only registration API and optional render-tail
  callbacks (e.g. Forge event bus hooks)."
  (:require [cn.li.mc1201.gui.cgui.runtime :as cgui-rt]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.owner-contract :as owner-contract]
            [cn.li.mcmod.gui.registry :as gui-reg]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.shim DelegatingCGuiContainerScreen]
           [net.minecraft.client.gui GuiGraphics]
           [cn.li.mc1201.gui CGuiContainerScreen]))

(def ^:private inv-tab-id "inv")

;; Vanilla AbstractContainerScreen defaults (MC 1.20.1 inventory GUI size).
(def default-image-width 176)
(def default-image-height 166)

(defn resolve-image-size
  "Resolve target imageWidth/imageHeight for a :cgui-screen-container map.

  Priority:
  1. Explicit :image-width / :image-height (absolute)
  2. :size-dx / :size-dy added to vanilla defaults (TechUI)
  3. nil — keep vanilla defaults unchanged"
  [cgui-screen]
  (if (or (contains? cgui-screen :image-width)
          (contains? cgui-screen :image-height))
    [(int (or (:image-width cgui-screen) default-image-width))
     (int (or (:image-height cgui-screen) default-image-height))]
    (let [dx (int (or (:size-dx cgui-screen) 0))
          dy (int (or (:size-dy cgui-screen) 0))]
      (when (or (not= 0 dx) (not= 0 dy))
        [(+ default-image-width dx)
         (+ default-image-height dy)]))))

(defn cgui-screen-container?
  [m]
  (and (map? m)
       (= (:type m) :cgui-screen-container)
       (contains? m :cgui)
       (contains? m :minecraft-container)))

(defn- slots-enabled-for-click?
  [cgui-screen]
  (if-let [current-atom (:current-tab-atom cgui-screen)]
    (= inv-tab-id (deref current-atom))
    true))

(defn- slots-visible?
  [cgui-screen]
  (slots-enabled-for-click? cgui-screen))

(defn- with-cgui-error
  [label f]
  (try
    (f)
    (catch Exception e
      (log/debug label (.getMessage e)))))

(defn- sync-root-bounds!
  [^CGuiContainerScreen screen left-atom top-atom]
  (reset! left-atom (.getGuiLeft screen))
  (reset! top-atom (.getGuiTop screen)))

(defn- apply-root-alignment!
  "When root widget has CENTER/CENTER alignment from XML, override left/top
  to center it on the full screen.  This matches upstream implementation
  CGuiScreen (full-screen overlay) where LambdaLib2 handled alignment.
  For roots without CENTER alignment (programmatic TechUI containers),
  left/top stay at the vanilla guiLeft/guiTop."
  [root left-atom top-atom screen-w screen-h]
  (let [tm (get @(:metadata root) :transform-meta {})
        align-w (:align-width tm)
        align-h (:align-height tm)
        [rw rh] (cgui-core/get-size root)]
    (when (= align-w :center)
      (reset! left-atom (long (/ (- (double screen-w) (double rw)) 2.0))))
    (when (= align-h :center)
      (reset! top-atom (long (/ (- (double screen-h) (double rh)) 2.0))))))

(defn owner-for-screen-menu
  "Resolve canonical client owner for a Minecraft menu's Clojure container."
  [menu]
  (when menu
    (some-> menu
            container-state/get-container-for-menu
            container-state/owner-from-container
            owner-contract/require-client-owner)))

(defn with-screen-client-owner
  "Execute f with *player-state-owner* bound from the menu's Clojure container."
  [menu f]
  (if-let [owner (owner-for-screen-menu menu)]
    (client-session/with-bound-client-owner owner f)
    (throw (ex-info "CGUI screen requires canonical client owner on menu container"
                    {:menu menu}))))

(defn- with-screen-cgui
  [menu label f]
  (with-screen-client-owner menu
    #(with-cgui-error label f)))

(defn create-cgui-container-screen
  "Build a CGuiContainerScreen proxy using shared widget runtime behavior.

  Optional opts:
  - :on-render-tail! (fn [screen gg mouse-x mouse-y partial-ticks])"
  [menu player-inventory title cgui-screen {:keys [on-render-tail!]}]
  (when-not (cgui-screen-container? cgui-screen)
    (throw (ex-info "Expected :cgui-screen-container map" {:got (type cgui-screen)})))
  (let [root (:cgui cgui-screen)
        left (atom 0)
        top (atom 0)
        target-size (resolve-image-size cgui-screen)
        ^DelegatingCGuiContainerScreen screen (doto (DelegatingCGuiContainerScreen. menu player-inventory title)
      (.withRender (fn [^DelegatingCGuiContainerScreen s ^GuiGraphics gg mouse-x mouse-y partial-ticks]
        (when root
          (with-screen-cgui menu "CGUI frame-tick error"
            #(do
               (cgui-rt/resize-root! root (.getXSize s) (.getYSize s))
               (cgui-rt/frame-tick! root {:partial-ticks partial-ticks}))))
        (if (slots-visible? cgui-screen)
          (.callSuperRender s gg (int mouse-x) (int mouse-y) (float partial-ticks))
          (do
            (.callSuperRenderBackground s gg)
            (sync-root-bounds! s left top)
            ;; Apply root CENTER/CENTER alignment (matching upstream
            ;; CGuiScreen full-screen overlay behavior).
            (when root
              (apply-root-alignment! root left top (.-width s) (.-height s)))
            (when root
              (with-screen-cgui menu "CGUI non-slot tab render error"
                #(cgui-rt/render-tree! gg root @left @top)))
            (.callSuperRenderTooltip s gg (int mouse-x) (int mouse-y))))
        (when on-render-tail!
          (with-screen-client-owner menu
            #(on-render-tail! s gg mouse-x mouse-y partial-ticks)))))

      (.withRenderLabels (fn [^DelegatingCGuiContainerScreen _s ^GuiGraphics _gg _mouse-x _mouse-y]
        (comment "skip labels")))

      (.withRenderBg (fn [^DelegatingCGuiContainerScreen s ^GuiGraphics gg _partial-ticks _mouse-x _mouse-y]
        (sync-root-bounds! s left top)
        ;; Apply root CENTER/CENTER alignment.
        (when root
          (apply-root-alignment! root left top (.-width s) (.-height s)))
        (when root
          (with-screen-cgui menu "CGUI renderBg error"
            #(cgui-rt/render-tree! gg root @left @top)))))

      (.withMouseClicked (fn [^DelegatingCGuiContainerScreen s mouse-x mouse-y button]
        (when root
          (with-screen-cgui menu "CGUI mouse-click error"
            #(cgui-rt/mouse-click! root (int mouse-x) (int mouse-y) @left @top button)))
        (if (slots-enabled-for-click? cgui-screen)
          (.callSuperMouseClicked s mouse-x mouse-y button)
          true)))

      (.withMouseReleased (fn [^DelegatingCGuiContainerScreen s mouse-x mouse-y button]
        (if (slots-enabled-for-click? cgui-screen)
          (.callSuperMouseReleased s mouse-x mouse-y button)
          true)))

      (.withMouseDragged (fn [^DelegatingCGuiContainerScreen s mouse-x mouse-y button drag-x drag-y]
        (when root
          (with-screen-cgui menu "CGUI mouse-drag error"
            #(cgui-rt/mouse-drag! root (int mouse-x) (int mouse-y) (int drag-x) (int drag-y) @left @top)))
        (if (slots-enabled-for-click? cgui-screen)
          (.callSuperMouseDragged s mouse-x mouse-y button drag-x drag-y)
          true)))

      (.withKeyPressed (fn [^DelegatingCGuiContainerScreen s key-code scan-code modifiers]
        ;; Per-screen key hook — matches original LambdaLib2 TreeScreen.keyTyped:
        ;;   if (key == KEY_ESCAPE) Option(gui.getWidget("link_page")).map(_.component[Cover].end())
        ;;   else super.keyTyped(ch, key)
        ;; Hook returns truthy → event consumed (skip CGUI dispatch + vanilla).
        ;; Hook returns nil/false → normal CGUI dispatch → vanilla fallback.
        (or (when-let [hook (:key-hook cgui-screen)]
              (hook key-code scan-code modifiers))
            (let [owns-key? (and root (cgui-rt/focused-widget-owns-key? root))]
              (when root
                (with-screen-cgui menu "CGUI key-input error"
                  #(cgui-rt/key-input! root key-code scan-code (char 0))))
              (if owns-key?
                true
                (.callSuperKeyPressed s key-code scan-code modifiers))))))

      (.withCharTyped (fn [^DelegatingCGuiContainerScreen s code-point modifiers]
        (let [owns-key? (and root (cgui-rt/focused-widget-owns-key? root))]
          (when root
            (with-screen-cgui menu "CGUI char-input error"
              #(cgui-rt/key-input! root 0 0 (char code-point))))
          (if owns-key?
            true
            (.callSuperCharTyped s (char code-point) modifiers)))))

      (.withRemoved (fn [^DelegatingCGuiContainerScreen _s]
        (when root
          (with-screen-cgui menu "CGUI dispose error"
            #(cgui-rt/dispose! root))))))]
    (when target-size
      (let [[w h] target-size]
        (.setImageSize screen w h)))
    screen))

(defn fallback-container-screen
  [menu player-inventory title]
  (doto (DelegatingCGuiContainerScreen. menu player-inventory title)
    (.withRenderBg (fn [^DelegatingCGuiContainerScreen s ^GuiGraphics gg _partial _mx _my]
      (let [left (.getGuiLeft s)
            top (.getGuiTop s)
            right (+ left (.getXSize s))
            bottom (+ top (.getYSize s))]
        (.fill gg left top right bottom (unchecked-int 0xC0101010))
        (.fill gg left top right bottom (unchecked-int 0xD0101010)))))))

(defn create-screen-or-fallback
  [gui-id menu player-inventory title factory-fn-kw {:keys [on-render-tail!]}]
  (let [factory-fn (when factory-fn-kw
                     (try
                       (gui-reg/get-screen-factory-fn factory-fn-kw)
                       (catch Exception e
                         (log/error "[SCREEN-FACTORY] Screen factory not registered for" factory-fn-kw ":" (.getMessage e))
                         nil)))]
    (if factory-fn
      (try
        (let [screen-data (client-session/with-current-client-owner
                            #(factory-fn menu player-inventory title))]
          (if (cgui-screen-container? screen-data)
            (create-cgui-container-screen menu player-inventory title screen-data {:on-render-tail! on-render-tail!})
            (fallback-container-screen menu player-inventory title)))
        (catch Throwable e
          (log/error "[SCREEN-FACTORY] Error creating CGui screen for GUI ID" gui-id ":" (.getMessage e))
          (fallback-container-screen menu player-inventory title)))
      (do
        (log/error "[SCREEN-FACTORY] Missing factory function, using fallback screen. gui-id=" gui-id "factory-fn-kw=" factory-fn-kw)
        (fallback-container-screen menu player-inventory title)))))