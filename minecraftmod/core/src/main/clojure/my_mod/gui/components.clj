(ns my-mod.gui.components
  "LambdaLib2 Component library wrapper - Common UI components"
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.events :as events])
  (:import [cn.lambdalib2.cgui.component Component
            DrawTexture TextBox ProgressBar ElementList
            DragBar Draggable Outline Tint Transform]
           [cn.lambdalib2.cgui Widget]
           [net.minecraft.util ResourceLocation]))

;; ============================================================================
;; Component Attachment
;; ============================================================================

(defn add-component!
  "Add a component to a widget"
  [^Widget widget ^Component component]
  (.addComponent widget component)
  widget)

(defn remove-component!
  "Remove a component from widget"
  [^Widget widget ^Component component]
  (.removeComponent widget component)
  widget)

(defn get-component
  "Get component of specific type from widget"
  [^Widget widget component-class]
  (.getComponent widget component-class))

;; ============================================================================
;; DrawTexture Component
;; ============================================================================

(defn texture
  "Create DrawTexture component
  
  Args:
  - texture: ResourceLocation or string path
  - u v: texture coordinates (default 0, 0)
  - w h: texture size (default widget size)
  
  Example:
  (texture \"my_mod:textures/gui/node.png\")
  (texture texture-loc 0 0 176 166)"
  ([texture-path]
   (let [texture (if (instance? ResourceLocation texture-path)
                   texture-path
                   (ResourceLocation. texture-path))]
     (DrawTexture. texture)))
  ([texture-path u v w h]
   (let [texture (if (instance? ResourceLocation texture-path)
                   texture-path
                   (ResourceLocation. texture-path))
         draw-tex (DrawTexture. texture)]
     (doto draw-tex
       (.setUV u v)
       (.setSize w h)))))

(defn set-texture!
  "Set texture on DrawTexture component"
  [^DrawTexture draw-tex texture-path]
  (let [texture (if (instance? ResourceLocation texture-path)
                  texture-path
                  (ResourceLocation. texture-path))]
    (.setTexture draw-tex texture))
  draw-tex)

(defn set-uv!
  "Set texture UV coordinates"
  [^DrawTexture draw-tex u v]
  (.setUV draw-tex u v)
  draw-tex)

;; ============================================================================
;; TextBox Component
;; ============================================================================

(defn text-box
  "Create TextBox component for displaying text
  
  Options:
  - :text - Initial text (default \"\")
  - :color - Text color as int (default white 0xFFFFFF)
  - :scale - Text scale (default 1.0)
  - :shadow? - Draw shadow (default true)
  
  Example:
  (text-box :text \"Energy: 1000\" :color 0x00FF00)"
  [& {:keys [text color scale shadow?]
      :or {text "" color 0xFFFFFF scale 1.0 shadow? true}}]
  (let [tb (TextBox.)]
    (doto tb
      (.setContent text)
      (.setColor color)
      (.setScale scale)
      (.setShadow shadow?))))

(defn set-text!
  "Update text content"
  [^TextBox text-box text]
  (.setContent text-box text)
  text-box)

(defn get-text
  "Get current text"
  [^TextBox text-box]
  (.getContent text-box))

(defn set-text-color!
  "Set text color"
  [^TextBox text-box color]
  (.setColor text-box color)
  text-box)

;; ============================================================================
;; ProgressBar Component
;; ============================================================================

(defn progress-bar
  "Create ProgressBar component
  
  Options:
  - :direction - :horizontal or :vertical (default :horizontal)
  - :progress - Initial progress 0.0-1.0 (default 0.0)
  - :color-full - Color when full (default 0x00FF00)
  - :color-empty - Color when empty (default 0x404040)
  
  Example:
  (progress-bar :direction :horizontal :progress 0.75)"
  [& {:keys [direction progress color-full color-empty]
      :or {direction :horizontal progress 0.0
           color-full 0x00FF00 color-empty 0x404040}}]
  (let [pb (ProgressBar.)
        is-vertical? (= direction :vertical)]
    (doto pb
      (.setDirection (if is-vertical?
                       ProgressBar$Direction/VERTICAL
                       ProgressBar$Direction/HORIZONTAL))
      (.setProgress progress)
      (.setFullColor color-full)
      (.setEmptyColor color-empty))))

(defn set-progress!
  "Set progress bar value (0.0 to 1.0)"
  [^ProgressBar pb value]
  (.setProgress pb (double (max 0.0 (min 1.0 value))))
  pb)

(defn get-progress
  "Get current progress"
  [^ProgressBar pb]
  (.getProgress pb))

;; ============================================================================
;; Transform Component
;; ============================================================================

(defn transform
  "Create Transform component for position/scale/rotation
  
  Options:
  - :translate [x y] - Translation offset
  - :scale s - Scale factor
  - :rotate deg - Rotation in degrees
  
  Example:
  (transform :translate [10 20] :scale 1.5)"
  [& {:keys [translate scale rotate]
      :or {translate [0 0] scale 1.0 rotate 0.0}}]
  (let [trans (Transform.)
        [x y] translate]
    (doto trans
      (.setTranslate x y)
      (.setScale scale)
      (.setRotate rotate))))

(defn set-translate!
  "Set translation offset"
  [^Transform trans x y]
  (.setTranslate trans x y)
  trans)

(defn set-component-scale!
  "Set scale factor"
  [^Transform trans scale]
  (.setScale trans scale)
  trans)

(defn set-rotate!
  "Set rotation in degrees"
  [^Transform trans degrees]
  (.setRotate trans degrees)
  trans)

;; ============================================================================
;; Outline Component
;; ============================================================================

(defn outline
  "Create Outline component (draws border)
  
  Options:
  - :color - Border color (default 0xFFFFFF)
  - :width - Border width (default 1.0)
  
  Example:
  (outline :color 0xFFD700 :width 2.0)"
  [& {:keys [color width]
      :or {color 0xFFFFFF width 1.0}}]
  (let [out (Outline.)]
    (doto out
      (.setColor color)
      (.setWidth width))))

(defn set-outline-color!
  "Set outline color"
  [^Outline outline color]
  (.setColor outline color)
  outline)

(defn set-outline-width!
  "Set outline width"
  [^Outline outline width]
  (.setWidth outline width)
  outline)

;; ============================================================================
;; Tint Component
;; ============================================================================

(defn tint
  "Create Tint component (colors entire widget)
  
  Args:
  - color: ARGB color as int (default 0xFFFFFFFF)
  
  Example:
  (tint 0x80FF0000) ; Semi-transparent red"
  ([]
   (Tint.))
  ([color]
   (let [t (Tint.)]
     (.setColor t color)
     t)))

(defn set-tint!
  "Set tint color"
  [^Tint tint-comp color]
  (.setColor tint-comp color)
  tint-comp)

(defn set-alpha!
  "Set tint alpha (0-255)"
  [^Tint tint-comp alpha]
  (let [current (.getColor tint-comp)
        rgb (bit-and current 0x00FFFFFF)
        new-color (bit-or rgb (bit-shift-left alpha 24))]
    (.setColor tint-comp new-color))
  tint-comp)

;; ============================================================================
;; Draggable Component
;; ============================================================================

(defn draggable
  "Create Draggable component (allows widget to be dragged)
  
  Example:
  (draggable)"
  []
  (Draggable.))

(defn set-draggable!
  "Enable/disable dragging"
  [^Draggable drag enabled?]
  (.setEnabled drag enabled?)
  drag)

;; ============================================================================
;; DragBar Component
;; ============================================================================

(defn drag-bar
  "Create DragBar component (title bar that drags parent)
  
  Options:
  - :text - Title text
  - :height - Bar height (default 12)
  
  Example:
  (drag-bar :text \"Wireless Node\" :height 14)"
  [& {:keys [text height]
      :or {text "" height 12}}]
  (let [db (DragBar.)]
    (doto db
      (.setTitle text)
      (.setHeight height))))

(defn set-drag-bar-title!
  "Set drag bar title text"
  [^DragBar drag-bar text]
  (.setTitle drag-bar text)
  drag-bar)

;; ============================================================================
;; ElementList Component
;; ============================================================================

(defn element-list
  "Create ElementList component (scrollable list)
  
  Options:
  - :spacing - Space between elements (default 2)
  
  Example:
  (element-list :spacing 4)"
  [& {:keys [spacing]
      :or {spacing 2}}]
  (let [el (ElementList.)]
    (.setSpacing el spacing)
    el))

(defn list-add!
  "Add element to list"
  [^ElementList elem-list ^Widget widget]
  (.addElement elem-list widget)
  elem-list)

(defn list-remove!
  "Remove element from list"
  [^ElementList elem-list ^Widget widget]
  (.removeElement elem-list widget)
  elem-list)

(defn list-clear!
  "Clear all elements from list"
  [^ElementList elem-list]
  (.clear elem-list)
  elem-list)

;; =========================================================================
;; Higher-level Widgets
;; =========================================================================

(defn text-field
  "Create a TextBox component that behaves like a simple text field.

  Note: This is a lightweight wrapper; advanced input handling should be
  attached via events in caller code.

  Options:
  - :text
  - :max-length (unused, kept for API compatibility)
  - :masked (unused, kept for API compatibility)
  - :placeholder (used as initial content if :text is empty)
  - :on-confirm (unused, kept for API compatibility)"
  [& {:keys [text placeholder]
      :or {text "" placeholder ""}}]
  (text-box :text (if (empty? text) placeholder text)))

(defn set-placeholder!
  "Set placeholder text if current content is empty"
  [^TextBox text-box placeholder]
  (when (empty? (.getContent text-box))
    (.setContent text-box placeholder))
  text-box)

(defn button
  "Create a simple clickable button widget.

  Options:
  - :text
  - :x :y :width :height
  - :color (unused, kept for API compatibility)
  - :hover-color (unused)
  - :text-color
  - :on-click"
  [& {:keys [text x y width height text-color on-click]
      :or {text "Button" x 0 y 0 width 60 height 14 text-color 0xFFFFFF}}]
  (let [widget (cgui/create-widget :pos [x y] :size [width height])
        label (text-box :text text :color text-color :scale 0.7 :shadow? true)]
    (add-component! widget (outline :color 0x404040 :width 1.0))
    (add-component! widget label)
    (when on-click
      (events/on-left-click widget (events/make-click-handler on-click)))
    widget))

(defn histogram
  "Create a simple histogram widget with a progress bar.

  Options:
  - :label
  - :x :y :width :height
  - :color
  - :value-fn :max-fn
  - :direction (:horizontal or :vertical)"
  [& {:keys [label x y width height color value-fn max-fn direction]
      :or {label "" x 0 y 0 width 60 height 40 color 0x00FF00
           value-fn (constantly 0) max-fn (constantly 1)
           direction :vertical}}]
  (let [panel (cgui/create-widget :pos [x y] :size [width height])
        bar (progress-bar :direction direction
                          :progress 0.0
                          :color-full color
                          :color-empty 0x404040)
        label-box (text-box :text label :color 0xFFFFFF :scale 0.6 :shadow? true)]
    (add-component! panel bar)
    (add-component! panel label-box)
    (events/on-frame panel
      (fn [_]
        (let [current (double (value-fn))
              max-val (double (max-fn))
              progress (if (> max-val 0.0) (/ current max-val) 0.0)]
          (set-progress! bar progress))))
    panel))

(defn property-field
  "Create a simple label/value property widget.

  Options:
  - :label
  - :x :y :width
  - :editable (unused, kept for API compatibility)
  - :masked (unused)
  - :label-color :value-color
  - :max-length (unused)
  - :on-change (unused)
  "
  [& {:keys [label x y width label-color value-color]
      :or {label "" x 0 y 0 width 70 label-color 0xAAAAAA value-color 0xFFFFFF}}]
  (let [widget (cgui/create-widget :pos [x y] :size [width 12])
        label-box (text-box :text (str label ": ") :color label-color :scale 0.7 :shadow? true)
        value-box (text-box :text "" :color value-color :scale 0.7 :shadow? true)]
    (add-component! widget label-box)
    (add-component! widget value-box)
    widget))

;; ============================================================================
;; Component Builder DSL
;; ============================================================================

(defn with-components
  "Add multiple components to a widget
  
  Args:
  - widget: Widget instance
  - components: Vector of Component instances
  
  Returns: the widget
  
  Example:
  (with-components widget
    [(texture \"gui/bg.png\")
     (text-box :text \"Hello\")
     (outline :color 0xFFFFFF)])"
  [widget components]
  (doseq [comp components]
    (add-component! widget comp))
  widget)

(defmacro components->
  "Thread-first macro for component addition
  
  Example:
  (components-> widget
    (add-component! (texture \"bg.png\"))
    (add-component! (text-box :text \"Hi\")))"
  [widget & forms]
  `(-> ~widget ~@forms))
