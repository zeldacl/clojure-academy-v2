(ns cn.li.mc262.gui.screen.impl
  "Shared reactive screen construction.

  Platform adapters supply only registration API and optional render-tail
  callbacks. The 26.2 host-container and reactive render tape are live."
  (:require [cn.li.mcbase.client.session :as client-session]
            [cn.li.mc262.gui.reactive.host-container :as reactive-host]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.registry :as gui-reg]
            [cn.li.mcmod.runtime.owner :as runtime-owner]))

(def default-image-width 176)
(def default-image-height 166)

(defn resolve-image-size
  "Resolve target imageWidth/imageHeight for a reactive screen-data map."
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
  [m]
  (and (map? m)
       (= (:type m) :reactive-container-screen)
       (contains? m :runtime)))

(defn owner-for-screen-menu
  [menu]
  (when menu
    (some-> menu
            container-state/get-container-for-menu
            container-state/owner-from-container
            runtime-owner/require-client-owner)))

(defn with-screen-client-owner
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
