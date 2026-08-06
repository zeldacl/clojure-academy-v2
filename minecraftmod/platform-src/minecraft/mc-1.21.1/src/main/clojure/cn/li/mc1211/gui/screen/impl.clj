(ns cn.li.mc1211.gui.screen.impl
  "Shared reactive screen construction.

  Platform adapters should supply only registration API and optional render-tail
  callbacks (e.g. Forge event bus hooks)."
  (:require [cn.li.mcbase.client.session :as client-session]
            [cn.li.mc1211.gui.reactive.host-container :as reactive-host]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.registry :as gui-reg]
            [cn.li.mcmod.runtime.owner :as runtime-owner])
  )

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
            runtime-owner/require-client-owner)))

(defn with-screen-client-owner
  "Execute f with player-state-owner bound from the menu's Clojure container."
  [menu f]
  (if-let [owner (owner-for-screen-menu menu)]
    (client-session/with-bound-client-owner owner f)
    (throw (ex-info "CGUI screen requires canonical client owner on menu container"
                    {:menu menu}))))

(defn create-screen!
  [gui-id menu player-inventory title factory-fn-kw _options]
  (let [factory-fn (gui-reg/get-screen-factory-fn factory-fn-kw)
        screen-data (client-session/with-current-client-owner
                      #(factory-fn menu player-inventory title))]
    (when-not (reactive-container-screen? screen-data)
      (throw (ex-info "Screen factory must return reactive container screen data"
                      {:gui-id gui-id
                       :factory-fn-kw factory-fn-kw
                       :returned-type (some-> screen-data type str)
                       :returned-type-key (:type screen-data)})))
    (reactive-host/create-tech-ui-container-screen
      (assoc screen-data :minecraft-container menu :screen-title (str title) :player-inventory player-inventory))))
