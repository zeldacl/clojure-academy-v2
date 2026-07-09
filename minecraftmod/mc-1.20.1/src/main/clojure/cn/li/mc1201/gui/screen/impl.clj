(ns cn.li.mc1201.gui.screen.impl
  "Shared reactive screen construction and fallback behavior.

  Platform adapters should supply only registration API and optional render-tail
  callbacks (e.g. Forge event bus hooks)."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.gui.reactive.host-container :as reactive-host]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.owner-contract :as owner-contract]
            [cn.li.mcmod.gui.registry :as gui-reg]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.shim DelegatingCGuiContainerScreen]
           [net.minecraft.client.gui GuiGraphics]))

;; Vanilla AbstractContainerScreen defaults (MC 1.20.1 inventory GUI size).
(def default-image-width 176)
(def default-image-height 166)

(defn resolve-image-size
  "Resolve target imageWidth/imageHeight for a reactive screen-data map.

  Priority:
  1. Explicit :image-width / :image-height (absolute)
  2. :size-dx / :size-dy added to vanilla defaults (TechUI)
  3. nil — keep vanilla defaults unchanged"
  [screen-data]
  (if (or (contains? screen-data :image-width)
          (contains? screen-data :image-height))
    [(int (or (:image-width screen-data) default-image-width))
     (int (or (:image-height screen-data) default-image-height))]
    (let [dx (int (or (:size-dx screen-data) 0))
          dy (int (or (:size-dy screen-data) 0))]
      (when (or (not= 0 dx) (not= 0 dy))
        [(+ default-image-width dx)
         (+ default-image-height dy)]))))

(defn reactive-container-screen?
  "True for the {:type :reactive-container-screen :runtime rt ...} shape
   returned by ac.gui.block-gui-reactive/create-screen and friends."
  [m]
  (and (map? m)
       (= (:type m) :reactive-container-screen)
       (contains? m :runtime)))

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
          (cond
            (reactive-container-screen? screen-data)
            (reactive-host/create-tech-ui-container-screen
              (assoc screen-data :minecraft-container menu :screen-title (str title) :player-inventory player-inventory))

            :else
            (fallback-container-screen menu player-inventory title)))
        (catch Throwable e
          (log/error "[SCREEN-FACTORY] Error creating CGui screen for GUI ID" gui-id ":" (.getMessage e))
          (fallback-container-screen menu player-inventory title)))
      (do
        (log/error "[SCREEN-FACTORY] Missing factory function, using fallback screen. gui-id=" gui-id "factory-fn-kw=" factory-fn-kw)
        (fallback-container-screen menu player-inventory title)))))