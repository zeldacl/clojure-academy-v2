(ns cn.li.mcmod.gui.cgui-core
  "Core widget model and transformations.
   Provides fundamental widget creation, hierarchy, and property management.
   Decoupled from components, events, and rendering to support diverse UI patterns."
  (:require [clojure.string :as str]))

(defn- new-widget
  "Internal: Create a new widget instance with base properties.
   Initializes all atomic properties and empty collections for children/components/events."
  [{:keys [name pos size scale z-level visible? widget-type]
    :or {name nil pos [0 0] size [0 0] scale 1.0 z-level 0 visible? true widget-type :widget}}]
  {:id (str (java.util.UUID/randomUUID))
   :widget-type widget-type
   :name (atom name)
   :pos (atom (vec pos))
   :size (atom (vec size))
   :scale (atom scale)
   :z-level (atom z-level)
   :visible? (atom visible?)
   :children (atom [])
   :components (atom [])
   :events (atom {})
   :metadata (atom {})})

(defn- widget? 
  "Check if value is a valid widget (has required structure)."
  [x]
  (and (map? x) (contains? x :children) (contains? x :components)))

(declare add-widget! copy-widget)

(defn create-widget
  "Create a basic widget with optional properties.
   :name - widget identifier (default nil)
   :pos - [x y] position (default [0 0])
   :size - [w h] size (default [0 0])
   :scale - scale factor (default 1.0)
   :z-level - rendering depth (default 0)"
  [& {:keys [name pos size scale z-level]
      :or {name nil pos [0 0] size [0 0] scale 1.0 z-level 0}}]
  (new-widget {:name name :pos pos :size size :scale scale :z-level z-level :widget-type :widget}))

(defn create-container
  "Create a container widget (can hold child widgets).
   Same properties as create-widget."
  [& {:keys [name pos size scale z-level]
      :or {name nil pos [0 0] size [0 0] scale 1.0 z-level 0}}]
  (new-widget {:name name :pos pos :size size :scale scale :z-level z-level :widget-type :container}))

(defn copy-widget
  "Deep copy a widget with all its properties and children.
   Preserves hierarchy structure and component state atoms."
  [widget]
  (let [clone (new-widget {:name @(-> widget :name)
                           :pos @(-> widget :pos)
                           :size @(-> widget :size)
                           :scale @(-> widget :scale)
                           :z-level @(-> widget :z-level)
                           :visible? @(-> widget :visible?)
                           :widget-type (:widget-type widget)})]
    ;; preserve transform-meta (alignWidth / alignHeight) from source widget
    (when-let [src-meta @(:metadata widget)]
      (swap! (:metadata clone) merge src-meta))
    (reset! (:components clone)
            (mapv (fn [comp]
                    (if (and (map? comp) (:state comp))
                      (assoc comp :state (atom @(:state comp)))
                      comp))
                  @(:components widget)))
    (doseq [child @(:children widget)]
      (add-widget! clone (copy-widget child)))
    clone))

;; ============================================================================
;; Hierarchy Operations
;; ============================================================================

(defn add-widget!
  "Add a child widget to a container.
   Returns the container for chaining."
  [container widget]
  (swap! (:children container) conj widget)
  container)

(defn remove-widget!
  "Remove a child widget from its parent container by identity.
   Returns the container for chaining."
  [container widget]
  (swap! (:children container) (fn [xs] (vec (remove #(= (:id %) (:id widget)) xs))))
  container)

(defn clear-widgets!
  "Remove all child widgets from a container.
   Returns the container for chaining."
  [container]
  (reset! (:children container) [])
  container)

(defn get-widgets 
  "Get all child widgets from a container as a vector."
  [container]
  (vec @(:children container)))

(defn get-draw-list 
  "Get rendering order of child widgets (currently same as child order)."
  [container]
  (get-widgets container))

(defn- find-child-by-name 
  "Internal: Find first child with matching name."
  [node child-name]
  (first (filter #(= child-name (or @(:name %) "")) (get-widgets node))))

(defn find-widget
  "Find widget by path string (e.g., \"root/panel/button\") or name.
   Returns nil if not found."
  [root name-or-path]
  (let [parts (str/split (str name-or-path) #"/")]
    (reduce (fn [node part]
              (when (and node (widget? node))
                (find-child-by-name node part)))
            root
            parts)))

;; ============================================================================
;; Property Access & Mutation
;; ============================================================================

(defn set-name!
  "Set the widget's name. Returns widget for chaining."
  [widget name]
  (reset! (:name widget) name)
  widget)

(defn get-name 
  "Get the widget's name (empty string if nil)."
  [widget]
  (or @(:name widget) ""))

(defn set-pos!
  "Set the widget's position [x y]. Returns widget for chaining."
  [widget x y]
  (reset! (:pos widget) [x y])
  widget)

(defn set-position! 
  "Alias for set-pos!. Returns widget for chaining."
  [widget x y]
  (set-pos! widget x y))

(defn get-pos 
  "Get the widget's position as [x y] vector."
  [widget]
  @(:pos widget))

(defn set-size!
  "Set the widget's size [w h]. Returns widget for chaining."
  [widget w h]
  (reset! (:size widget) [w h])
  widget)

(defn get-size 
  "Get the widget's size as [w h] vector."
  [widget]
  @(:size widget))

(defn get-width 
  "Get the widget's width as a double."
  [widget]
  (double (first (get-size widget))))

(defn get-height 
  "Get the widget's height as a double."
  [widget]
  (double (second (get-size widget))))

(defn set-scale!
  "Set the widget's scale factor. Returns widget for chaining."
  [widget scale]
  (reset! (:scale widget) scale)
  widget)

(defn set-w-align!
  "Set horizontal alignment metadata on widget.
   Accepts keywords or strings: :left/:center/:right or \"left\"/\"center\"/\"right\".
   Stores value under [:transform-meta :align-width] in widget metadata.
   Returns widget for chaining."
  [widget align]
  (when widget
    (let [a (if (keyword? align) align (keyword (str/lower-case (str align))))]
      (swap! (:metadata widget) assoc-in [:transform-meta :align-width] a))
    widget))

(defn set-h-align!
  "Set vertical alignment metadata on widget.
   Accepts keywords or strings: :top/:middle/:bottom or \"top\"/\"middle\"/\"bottom\".
   Stores value under [:transform-meta :align-height] in widget metadata.
   Returns widget for chaining."
  [widget align]
  (when widget
    (let [a (if (keyword? align) align (keyword (str/lower-case (str align))))]
      (swap! (:metadata widget) assoc-in [:transform-meta :align-height] a))
    widget))

(defn set-z-level!
  "Set the widget's rendering depth. Returns widget for chaining."
  [widget z]
  (reset! (:z-level widget) z)
  widget)

(defn set-visible!
  "Set widget visibility. Returns widget for chaining."
  [widget visible?]
  (reset! (:visible? widget) (boolean visible?))
  widget)

(defn visible? 
  "Check if widget is visible."
  [widget]
  (boolean @(:visible? widget)))

(defn widget->map
  "Convert widget to a plain map representation (recursive for children).
   Useful for serialization or debugging."
  [widget]
  {:id (:id widget)
   :name (get-name widget)
   :widget-type (:widget-type widget)
   :pos (get-pos widget)
   :size (get-size widget)
   :visible? (visible? widget)
   :children (mapv widget->map (get-widgets widget))})
