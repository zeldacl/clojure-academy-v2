(ns my-mod.gui.cgui
  "LambdaLib2 CGui system wrapper - Clojure-friendly interface"
  (:import [cn.lambdalib2.cgui Widget WidgetContainer CGui CGuiScreen CGuiScreenContainer]))

;; ============================================================================
;; Widget Creation and Management
;; ============================================================================

(defn create-widget
  "Create a basic Widget instance
  
  Options:
  - :pos [x y] - Position (default [0 0])
  - :size [w h] - Size (default [0 0])
  - :scale s - Scale factor (default 1.0)
  - :z-level z - Z-level for rendering order (default 0)"
  [& {:keys [pos size scale z-level]
      :or {pos [0 0] size [0 0] scale 1.0 z-level 0}}]
  (let [[x y] pos
        [w h] size
        widget (Widget.)]
    (doto widget
      (.pos x y)
      (.size w h)
      (.scale scale)
      (.zLevel z-level))))

(defn create-container
  "Create a WidgetContainer (can hold child widgets)
  
  Options: same as create-widget"
  [& {:keys [pos size scale z-level]
      :or {pos [0 0] size [0 0] scale 1.0 z-level 0}}]
  (let [[x y] pos
        [w h] size
        container (WidgetContainer.)]
    (doto container
      (.pos x y)
      (.size w h)
      (.scale scale)
      (.zLevel z-level))))

;; ============================================================================
;; Widget Tree Operations
;; ============================================================================

(defn add-widget!
  "Add a child widget to a container
  
  Args:
  - container: WidgetContainer instance
  - widget: Widget instance to add
  
  Returns: the container (for chaining)"
  [^WidgetContainer container ^Widget widget]
  (.addWidget container widget)
  container)

(defn remove-widget!
  "Remove a child widget from container"
  [^WidgetContainer container ^Widget widget]
  (.removeWidget container widget)
  container)

(defn clear-widgets!
  "Remove all child widgets from container"
  [^WidgetContainer container]
  (.removeAllWidgets container)
  container)

(defn get-widgets
  "Get all child widgets from container"
  [^WidgetContainer container]
  (vec (.getDrawList container)))

;; ============================================================================
;; Widget Properties
;; ============================================================================

(defn set-pos!
  "Set widget position"
  [^Widget widget x y]
  (.pos widget x y)
  widget)

(defn get-pos
  "Get widget position as [x y]"
  [^Widget widget]
  [(.x widget) (.y widget)])

(defn set-size!
  "Set widget size"
  [^Widget widget w h]
  (.size widget w h)
  widget)

(defn get-size
  "Get widget size as [w h]"
  [^Widget widget]
  [(.transform widget.width) (.transform widget.height)])

(defn set-scale!
  "Set widget scale"
  [^Widget widget scale]
  (.scale widget scale)
  widget)

(defn set-z-level!
  "Set widget z-level (rendering order)"
  [^Widget widget z]
  (.zLevel widget z)
  widget)

(defn set-visible!
  "Set widget visibility"
  [^Widget widget visible?]
  (set! (.doesDraw widget) visible?)
  widget)

(defn visible?
  "Check if widget is visible"
  [^Widget widget]
  (.doesDraw widget))

;; ============================================================================
;; CGui Creation
;; ============================================================================

(defn create-cgui
  "Create a CGui (root container for component-based GUI)
  
  Returns: CGui instance"
  []
  (CGui.))

(defn get-root
  "Get root widget container from CGui"
  [^CGui cgui]
  (.getRoot cgui))

(defn cgui-add-widget!
  "Add widget to CGui root"
  [^CGui cgui ^Widget widget]
  (add-widget! (get-root cgui) widget))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-cgui-screen
  "Create a CGuiScreen (pure GUI, no Container)
  
  Args:
  - cgui: CGui instance
  
  Returns: CGuiScreen instance"
  [^CGui cgui]
  (CGuiScreen. cgui))

(defn create-cgui-screen-container
  "Create a CGuiScreenContainer (GUI with Container)
  
  Args:
  - cgui: CGui instance
  - container: Minecraft Container instance
  
  Returns: CGuiScreenContainer instance"
  [^CGui cgui container]
  (CGuiScreenContainer. cgui container))

;; ============================================================================
;; Widget Builder DSL
;; ============================================================================

(defn build-widget-tree
  "Build a widget tree from Clojure data structure
  
  Format:
  {:type :container | :widget
   :pos [x y]
   :size [w h]
   :components [...] ; Component instances to add
   :children [...]}  ; Recursive widget specs
  
  Example:
  {:type :container
   :pos [0 0]
   :size [176 166]
   :children [{:type :widget :pos [10 10] :size [50 50]}]}"
  [spec]
  (let [{:keys [type pos size scale z-level components children]
         :or {type :widget pos [0 0] size [0 0] scale 1.0 z-level 0}} spec
        widget-fn (if (= type :container) create-container create-widget)
        widget (widget-fn :pos pos :size size :scale scale :z-level z-level)]
    
    ;; Add components
    (doseq [component components]
      (.addComponent widget component))
    
    ;; Add children (only for containers)
    (when (and (= type :container) (seq children))
      (doseq [child-spec children]
        (add-widget! widget (build-widget-tree child-spec))))
    
    widget))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn widget->map
  "Convert widget to Clojure map (for debugging)"
  [^Widget widget]
  {:class (.getSimpleName (.getClass widget))
   :pos (get-pos widget)
   :size (get-size widget)
   :visible? (visible? widget)
   :children (when (instance? WidgetContainer widget)
               (mapv widget->map (get-widgets widget)))})
