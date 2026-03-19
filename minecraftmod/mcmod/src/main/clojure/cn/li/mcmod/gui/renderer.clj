(ns my-mod.gui.renderer
  "GUI rendering abstraction for version-specific implementations"
  (:require [my-mod.util.log :as log]))

;; Multimethod for version-specific GUI rendering
(def ^:dynamic *forge-version* nil)

;; Color constants
(def color-white 0xFFFFFF)
(def color-gray 0x404040)
(def color-dark-gray 0x202020)
(def color-light-gray 0xC0C0C0)

;; Render context protocol
(defprotocol IRenderContext
  (draw-background [this x y width height])
  (draw-texture [this texture x y u v width height])
  (draw-text [this text x y color])
  (draw-button [this x y width height text enabled?])
  (draw-slot [this x y has-item?])
  (get-mouse-x [this])
  (get-mouse-y [this]))

;; Abstract rendering operations
(defmulti create-render-context
  "Create a version-specific render context"
  (fn [_graphics _gui-instance] *forge-version*))

(defmulti render-gui-background
  "Render the GUI background"
  (fn [_render-ctx _gui-spec _left-pos _top-pos] *forge-version*))

(defmulti render-gui-slots
  "Render all slots"
  (fn [_render-ctx _gui-instance _left-pos _top-pos] *forge-version*))

(defmulti render-gui-buttons
  "Render all buttons"
  (fn [_render-ctx _gui-instance _left-pos _top-pos _mouse-x _mouse-y] *forge-version*))

(defmulti render-gui-labels
  "Render all labels"
  (fn [_render-ctx _gui-spec _left-pos _top-pos] *forge-version*))

(defmulti render-gui-tooltips
  "Render tooltips for hovered elements"
  (fn [_render-ctx _gui-instance _mouse-x _mouse-y] *forge-version*))

;; Default implementations
(defmethod create-render-context :default [_ _]
  (throw (ex-info "No render context implementation for version"
                  {:version *forge-version*})))

(defmethod render-gui-background :default [_ _ _ _]
  (log/info "render-gui-background not implemented for" *forge-version*))

(defmethod render-gui-slots :default [_ _ _ _]
  (log/info "render-gui-slots not implemented for" *forge-version*))

(defmethod render-gui-buttons :default [_ _ _ _ _ _]
  (log/info "render-gui-buttons not implemented for" *forge-version*))

(defmethod render-gui-labels :default [_ _ _ _]
  (log/info "render-gui-labels not implemented for" *forge-version*))

(defmethod render-gui-tooltips :default [_ _ _ _]
  (log/info "render-gui-tooltips not implemented for" *forge-version*))

;; High-level rendering function
(defn render-gui
  "Main GUI rendering function - calls version-specific implementations"
  [graphics gui-instance left-pos top-pos mouse-x mouse-y]
  (let [render-ctx (create-render-context graphics gui-instance)
        gui-spec (:spec gui-instance)]
    
    ;; Render background
    (render-gui-background render-ctx gui-spec left-pos top-pos)
    
    ;; Render slots
    (render-gui-slots render-ctx gui-instance left-pos top-pos)
    
    ;; Render labels
    (render-gui-labels render-ctx gui-spec left-pos top-pos)
    
    ;; Render buttons
    (render-gui-buttons render-ctx gui-instance left-pos top-pos mouse-x mouse-y)
    
    ;; Render tooltips
    (render-gui-tooltips render-ctx gui-instance mouse-x mouse-y)))

;; Button hit testing
(defn button-hit-test
  "Check if mouse position is over a button"
  [button-spec left-pos top-pos mouse-x mouse-y]
  (let [btn-x (+ left-pos (:x button-spec))
        btn-y (+ top-pos (:y button-spec))
        btn-w (:width button-spec)
        btn-h (:height button-spec)]
    (and (>= mouse-x btn-x)
         (< mouse-x (+ btn-x btn-w))
         (>= mouse-y btn-y)
         (< mouse-y (+ btn-y btn-h)))))

;; Slot hit testing
(defn slot-hit-test
  "Check if mouse position is over a slot"
  [slot-spec left-pos top-pos mouse-x mouse-y]
  (let [slot-x (+ left-pos (:x slot-spec))
        slot-y (+ top-pos (:y slot-spec))
        slot-size 18] ; Standard Minecraft slot size
    (and (>= mouse-x slot-x)
         (< mouse-x (+ slot-x slot-size))
         (>= mouse-y slot-y)
         (< mouse-y (+ slot-y slot-size)))))

;; Find clicked button
(defn find-clicked-button
  "Find which button was clicked, if any"
  [gui-spec left-pos top-pos mouse-x mouse-y]
  (->> (:buttons gui-spec)
       (map-indexed vector)
       (filter (fn [[_idx btn]]
                 (button-hit-test btn left-pos top-pos mouse-x mouse-y)))
       (first)
       (first)))

;; Find clicked slot
(defn find-clicked-slot
  "Find which slot was clicked, if any"
  [gui-spec left-pos top-pos mouse-x mouse-y]
  (->> (:slots gui-spec)
       (filter (fn [slot]
                 (slot-hit-test slot left-pos top-pos mouse-x mouse-y)))
       (first)
       (:index)))
