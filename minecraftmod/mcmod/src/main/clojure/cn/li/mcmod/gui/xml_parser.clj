(ns cn.li.mcmod.gui.xml-parser
  "XML Layout Parser - Converts XML GUI layouts to DSL specifications
  
  This parser reads XML files (inspired by AcademyCraft's CGUIDocument format)
  and converts them into Clojure GUI DSL specifications.
  
  XML Format:
  - Root/Widget: Container widget
  - Component: Property specifications (Transform, DrawTexture, etc.)
  - Slot, Button, Label, etc.: UI elements
  
  Conversion Flow:
  XML File → XML AST → GuiSpec Map → DSL Registration"
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; XML Parsing Utilities
;; ============================================================================

(defn get-element
  "Get first child element with matching tag"
  [parent tag]
  (first (filter #(= (:tag %) tag) (:content parent))))

(defn get-elements
  "Get all child elements with matching tag"
  [parent tag]
  (filter #(= (:tag %) tag) (:content parent)))

(defn get-text
  "Get text content of element"
  [element]
  (first (filter string? (:content element))))

(defn normalize-xml-texture
  "Normalize texture strings read from XML layouts.
   - preserve absolute assets/... paths (leading assets/)
   - strip placeholder namespace 'academy:' to return the relative path
   - leave other namespaced references untouched"
  [s]
  (when s
    (let [s (str/trim s)]
      (cond
        (str/starts-with? s "assets/") s
        (str/includes? s ":")
        (let [[ns path] (str/split s #":" 2)]
          (if (= ns "academy") path s))
        :else s))))

(defn parse-float
  "Parse float from string, with default"
  [s default]
  (try
    (Float/parseFloat (str/trim s))
    (catch Exception _ default)))

(defn parse-int
  "Parse integer from string, with default"
  [s default]
  (try
    (Integer/parseInt (str/trim s))
    (catch Exception _ default)))

(defn parse-bool
  "Parse boolean from string"
  [s]
  (= (str/lower-case (str/trim s)) "true"))

(defn parse-color
  "Parse hex color string (#RRGGBB or #RRGGBBAA) to integer"
  [s]
  (try
    (if (str/starts-with? s "#")
      (Long/parseLong (subs s 1) 16)
      (Long/parseLong s 16))
    (catch Exception _
      0xFFFFFF)))

;; ============================================================================
;; Component Parsing
;; ============================================================================

(defn parse-transform
  "Parse Transform component"
  [component]
  (let [get-val (fn [tag default]
                  (if-let [elem (get-element component tag)]
                    (parse-float (get-text elem) default)
                    default))]
    {:width (get-val :width 176.0)
     :height (get-val :height 187.0)
     :x (get-val :x 0.0)
     :y (get-val :y 0.0)
     :scale (get-val :scale 1.0)
     :does-draw (if-let [elem (get-element component :doesDraw)]
                  (parse-bool (get-text elem))
                  true)}))

(defn parse-draw-texture
  "Parse DrawTexture component"
  [component]
  (let [texture-elem (get-element component :texture)
        color-elem (get-element component :color)]
    {:texture (when texture-elem (normalize-xml-texture (get-text texture-elem)))
     :color (if color-elem
              {:red (parse-int (get-text (get-element color-elem :red)) 255)
               :green (parse-int (get-text (get-element color-elem :green)) 255)
               :blue (parse-int (get-text (get-element color-elem :blue)) 255)
               :alpha (parse-int (get-text (get-element color-elem :alpha)) 255)}
              {:red 255 :green 255 :blue 255 :alpha 255})
     :z-level (if-let [elem (get-element component :zLevel)]
                (parse-float (get-text elem) 0.0)
                0.0)}))

;; ============================================================================
;; UI Element Parsing
;; ============================================================================

(defn parse-slot
  "Parse Slot element"
  [slot-elem]
  {:index (parse-int (get-text (get-element slot-elem :index)) 0)
   :x (parse-int (get-text (get-element slot-elem :x)) 0)
   :y (parse-int (get-text (get-element slot-elem :y)) 0)
   :filter (when-let [f (get-element slot-elem :filter)]
             (keyword (get-text f)))
   :tooltip (when-let [t (get-element slot-elem :tooltip)]
              (get-text t))})

(defn parse-button
  "Parse Button element"
  [button-elem]
  {:id (or (:name (:attrs button-elem)) "button")
   :x (parse-int (get-text (get-element button-elem :x)) 0)
   :y (parse-int (get-text (get-element button-elem :y)) 0)
   :width (parse-int (get-text (get-element button-elem :width)) 20)
   :height (parse-int (get-text (get-element button-elem :height)) 20)
  :texture (when-let [t (get-element button-elem :texture)]
          (normalize-xml-texture (get-text t)))
   :scale (if-let [s (get-element button-elem :scale)]
            (parse-float (get-text s) 1.0)
            1.0)
   :page-id (when-let [p (get-element button-elem :pageId)]
              (keyword (get-text p)))
   :tooltip (when-let [t (get-element button-elem :tooltip)]
              (get-text t))})

(defn parse-label
  "Parse Label element"
  [label-elem]
  {:x (parse-int (get-text (get-element label-elem :x)) 0)
   :y (parse-int (get-text (get-element label-elem :y)) 0)
   :text (when-let [t (get-element label-elem :text)]
           (get-text t))
   :color (if-let [c (get-element label-elem :color)]
            (parse-color (get-text c))
            0x404040)})

(defn parse-histogram
  "Parse Histogram element"
  [hist-elem]
  {:name (or (:name (:attrs hist-elem)) "histogram")
   :label (when-let [l (get-element hist-elem :label)]
            (get-text l))
   :type (when-let [t (get-element hist-elem :type)]
           (keyword (get-text t)))
   :color (if-let [c (get-element hist-elem :color)]
            (parse-color (get-text c))
            0xFFFFFF)
   :y (parse-int (get-text (get-element hist-elem :y)) 0)
   :height (parse-int (get-text (get-element hist-elem :height)) 40)})

(defn parse-property
  "Parse Property element"
  [prop-elem]
  {:name (or (:name (:attrs prop-elem)) "property")
   :label (when-let [l (get-element prop-elem :label)]
            (get-text l))
   :editable (if-let [e (get-element prop-elem :editable)]
               (parse-bool (get-text e))
               false)
   :masked (if-let [m (get-element prop-elem :masked)]
             (parse-bool (get-text m))
             false)
   :max-length (parse-int (get-text (get-element prop-elem :maxLength)) 32)
   :requires-owner (if-let [r (get-element prop-elem :requiresOwner)]
                      (parse-bool (get-text r))
                      false)})

(defn parse-animation
  "Parse Animation element"
  [anim-elem]
  (let [states (get-elements anim-elem :state)
        parse-state (fn [state-elem]
                      {:id (keyword (:id (:attrs state-elem)))
                       :begin-frame (parse-int (get-text (get-element state-elem :beginFrame)) 0)
                       :frame-count (parse-int (get-text (get-element state-elem :frameCount)) 1)
                       :frame-time (parse-int (get-text (get-element state-elem :frameTime)) 1000)})]
    {:name (or (:name (:attrs anim-elem)) "animation")
    :texture (when-let [t (get-element anim-elem :texture)]
         (normalize-xml-texture (get-text t)))
     :total-frames (parse-int (get-text (get-element anim-elem :totalFrames)) 10)
     :states (mapv parse-state states)}))

;; ============================================================================
;; Widget Parsing
;; ============================================================================

(defn parse-widget
  "Parse Widget element recursively"
  [widget-elem]
  (let [name (or (:name (:attrs widget-elem)) "widget")
        components (get-elements widget-elem :Component)
        transform (when-let [t (first (filter #(= "Transform" (:class (:attrs %))) components))]
                    (parse-transform t))
        draw-texture (when-let [dt (first (filter #(= "DrawTexture" (:class (:attrs %))) components))]
                       (parse-draw-texture dt))
        child-widgets (get-elements widget-elem :Widget)
        slots (get-elements widget-elem :Slot)
        buttons (get-elements widget-elem :Button)
        labels (get-elements widget-elem :Label)
        histograms (get-elements widget-elem :Histogram)
        properties (when-let [pl (get-element widget-elem :PropertyList)]
                     (get-elements pl :Property))
        animations (get-elements widget-elem :Animation)]
    {:name name
     :transform transform
     :texture draw-texture
     :children (mapv parse-widget child-widgets)
     :slots (mapv parse-slot slots)
     :buttons (mapv parse-button buttons)
     :labels (mapv parse-label labels)
     :histograms (mapv parse-histogram histograms)
     :properties (mapv parse-property properties)
     :animations (mapv parse-animation animations)}))

;; ============================================================================
;; Top-Level Parsing
;; ============================================================================

(defn parse-xml-layout
  "Parse XML layout file into GUI specification
  
  Args:
  - xml-path: Path to XML file (as resource, e.g., 'assets/my_mod/gui/layouts/page.xml')
  
  Returns: Parsed widget tree map"
  [xml-path]
  (try
    (log/info "Parsing XML layout:" xml-path)
    (let [xml-resource (io/resource xml-path)
          _ (when-not xml-resource
              (throw (ex-info (str "XML file not found: " xml-path) {:path xml-path})))
        parsed (xml/parse (io/input-stream xml-resource))
          root-widget (get-element parsed :Widget)]
      (when-not root-widget
        (throw (ex-info "No Widget element found in XML" {:path xml-path})))
      (let [result (parse-widget root-widget)]
        (log/info "Successfully parsed XML layout:" xml-path)
        result))
    (catch Exception e
      (log/error "Failed to parse XML layout:" xml-path "-" ((ex-message e)))
      (throw e))))

;; ============================================================================
;; DSL Conversion
;; ============================================================================

(defn xml-to-dsl-spec
  "Convert parsed XML widget tree to DSL GuiSpec format
  
  Args:
  - widget-tree: Parsed widget tree from parse-xml-layout
  - gui-id: GUI identifier string
  
  Returns: GuiSpec map compatible with gui/dsl.clj"
  [widget-tree gui-id]
  (let [transform (or (:transform widget-tree) {})
        main-texture (:texture widget-tree)
        ;; Collect all slots from widget tree recursively
        collect-slots (fn collect [w]
                        (concat (:slots w)
                                (mapcat collect (:children w))))
        ;; Collect all buttons
        collect-buttons (fn collect [w]
                          (concat (:buttons w)
                                  (mapcat collect (:children w))))
        ;; Collect all labels
        collect-labels (fn collect [w]
                         (concat (:labels w)
                                 (mapcat collect (:children w))))
        all-slots (collect-slots widget-tree)
        all-buttons (collect-buttons widget-tree)
        all-labels (collect-labels widget-tree)]
    {:id gui-id
     :title (or (:title widget-tree) "GUI")
     :width (or (:width transform) 176.0)
     :height (or (:height transform) 187.0)
     :background (or (:texture main-texture) :default)
     :slots all-slots
     :buttons all-buttons
     :labels all-labels
     :widget-tree widget-tree})) ; Keep original tree for advanced usage

;; ============================================================================
;; Resource Path Resolution
;; ============================================================================

(defn resolve-gui-layout-path
  "Resolve GUI layout path from mod namespace format
  
  Args:
  - layout-name: Short name like 'page_wireless_node'
  
  Returns: Full resource path"
  [layout-name]
  (str "assets/my_mod/gui/layouts/" layout-name ".xml"))

;; ============================================================================
;; Helper: Load and Convert
;; ============================================================================

(defn load-gui-from-xml
  "Load GUI specification from XML file
  
  Args:
  - gui-id: GUI identifier
  - layout-name: XML layout file name (without extension)
  
  Returns: GuiSpec map ready for DSL registration"
  [gui-id layout-name]
  (let [xml-path (resolve-gui-layout-path layout-name)
        widget-tree (parse-xml-layout xml-path)]
    (xml-to-dsl-spec widget-tree gui-id)))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Load wireless node GUI from XML
  (def node-spec (load-gui-from-xml "wireless-node-gui" "page_wireless_node"))
  
  ;; Inspect parsed spec
  (keys node-spec)
  ; => (:id :title :width :height :background :slots :buttons :labels :widget-tree)
  
  (:slots node-spec)
  ; => [{:index 0, :x 42, :y 10, :filter :energy_item, :tooltip "..."}
  ;     {:index 1, :x 42, :y 80, :filter :energy_item, :tooltip "..."}]
  
  ;; Access widget tree for advanced features
  (get-in node-spec [:widget-tree :children])
  ; => Vector of child widgets with full hierarchy
  )
