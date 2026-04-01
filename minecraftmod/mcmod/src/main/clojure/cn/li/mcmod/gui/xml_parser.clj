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
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.util.parse :as parse]
            [cn.li.mcmod.util.xml :as x]))

;; ============================================================================
;; Component Parsing
;; ============================================================================

(defn parse-transform
  "Parse Transform component"
  [component]
  (let [get-val (fn [tag default]
                  (if-let [elem (x/get-element component tag)]
                    (parse/parse-float (x/get-text elem) default)
                    default))]
    {:width (get-val :width 176.0)
     :height (get-val :height 187.0)
     :x (get-val :x 0.0)
     :y (get-val :y 0.0)
     :scale (get-val :scale 1.0)
     :does-draw (if-let [elem (x/get-element component :doesDraw)]
                  (parse/parse-bool (x/get-text elem))
                  true)}))

(defn parse-draw-texture
  "Parse DrawTexture component"
  [component]
  (let [texture-elem (x/get-element component :texture)
        color-elem (x/get-element component :color)]
    {:texture (when texture-elem (x/normalize-xml-texture (x/get-text texture-elem)))
     :color (if color-elem
              {:red (parse/parse-int (x/get-text (x/get-element color-elem :red)) 255)
               :green (parse/parse-int (x/get-text (x/get-element color-elem :green)) 255)
               :blue (parse/parse-int (x/get-text (x/get-element color-elem :blue)) 255)
               :alpha (parse/parse-int (x/get-text (x/get-element color-elem :alpha)) 255)}
              {:red 255 :green 255 :blue 255 :alpha 255})
     :z-level (if-let [elem (x/get-element component :zLevel)]
                (parse/parse-float (x/get-text elem) 0.0)
                0.0)}))

;; ============================================================================
;; UI Element Parsing
;; ============================================================================

(defn parse-slot
  "Parse Slot element"
  [slot-elem]
  {:index (parse/parse-int (x/get-text (x/get-element slot-elem :index)) 0)
   :x (parse/parse-int (x/get-text (x/get-element slot-elem :x)) 0)
   :y (parse/parse-int (x/get-text (x/get-element slot-elem :y)) 0)
   :filter (when-let [f (x/get-element slot-elem :filter)]
             (keyword (x/get-text f)))
   :tooltip (when-let [t (x/get-element slot-elem :tooltip)]
              (x/get-text t))})

(defn parse-button
  "Parse Button element"
  [button-elem]
  {:id (or (:name (:attrs button-elem)) "button")
   :x (parse/parse-int (x/get-text (x/get-element button-elem :x)) 0)
   :y (parse/parse-int (x/get-text (x/get-element button-elem :y)) 0)
   :width (parse/parse-int (x/get-text (x/get-element button-elem :width)) 20)
   :height (parse/parse-int (x/get-text (x/get-element button-elem :height)) 20)
  :texture (when-let [t (x/get-element button-elem :texture)]
          (x/normalize-xml-texture (x/get-text t)))
   :scale (if-let [s (x/get-element button-elem :scale)]
            (parse/parse-float (x/get-text s) 1.0)
            1.0)
   :page-id (when-let [p (x/get-element button-elem :pageId)]
              (keyword (x/get-text p)))
   :tooltip (when-let [t (x/get-element button-elem :tooltip)]
              (x/get-text t))})

(defn parse-label
  "Parse Label element"
  [label-elem]
  {:x (parse/parse-int (x/get-text (x/get-element label-elem :x)) 0)
   :y (parse/parse-int (x/get-text (x/get-element label-elem :y)) 0)
   :text (when-let [t (x/get-element label-elem :text)]
           (x/get-text t))
   :color (if-let [c (x/get-element label-elem :color)]
            (parse/parse-color (x/get-text c))
            0x404040)})

(defn parse-histogram
  "Parse Histogram element"
  [hist-elem]
  {:name (or (:name (:attrs hist-elem)) "histogram")
   :label (when-let [l (x/get-element hist-elem :label)]
            (x/get-text l))
   :type (when-let [t (x/get-element hist-elem :type)]
           (keyword (x/get-text t)))
   :color (if-let [c (x/get-element hist-elem :color)]
            (parse/parse-color (x/get-text c))
            0xFFFFFF)
   :y (parse/parse-int (x/get-text (x/get-element hist-elem :y)) 0)
   :height (parse/parse-int (x/get-text (x/get-element hist-elem :height)) 40)})

(defn parse-property
  "Parse Property element"
  [prop-elem]
  {:name (or (:name (:attrs prop-elem)) "property")
   :label (when-let [l (x/get-element prop-elem :label)]
            (x/get-text l))
   :editable (if-let [e (x/get-element prop-elem :editable)]
               (parse/parse-bool (x/get-text e))
               false)
   :masked (if-let [m (x/get-element prop-elem :masked)]
             (parse/parse-bool (x/get-text m))
             false)
   :max-length (parse/parse-int (x/get-text (x/get-element prop-elem :maxLength)) 32)
   :requires-owner (if-let [r (x/get-element prop-elem :requiresOwner)]
                      (parse/parse-bool (x/get-text r))
                      false)})

(defn parse-animation
  "Parse Animation element"
  [anim-elem]
  (let [states (x/get-elements anim-elem :state)
        parse-state (fn [state-elem]
                      {:id (keyword (:id (:attrs state-elem)))
                       :begin-frame (parse/parse-int (x/get-text (x/get-element state-elem :beginFrame)) 0)
                       :frame-count (parse/parse-int (x/get-text (x/get-element state-elem :frameCount)) 1)
                       :frame-time (parse/parse-int (x/get-text (x/get-element state-elem :frameTime)) 1000)})]
    {:name (or (:name (:attrs anim-elem)) "animation")
    :texture (when-let [t (x/get-element anim-elem :texture)]
         (x/normalize-xml-texture (x/get-text t)))
     :total-frames (parse/parse-int (x/get-text (x/get-element anim-elem :totalFrames)) 10)
     :states (mapv parse-state states)}))

;; ============================================================================
;; Widget Parsing
;; ============================================================================

(defn parse-widget
  "Parse Widget element recursively"
  [widget-elem]
  (let [name (or (:name (:attrs widget-elem)) "widget")
        components (x/get-elements widget-elem :Component)
        transform (when-let [t (first (filter #(= "Transform" (:class (:attrs %))) components))]
                    (parse-transform t))
        draw-texture (when-let [dt (first (filter #(= "DrawTexture" (:class (:attrs %))) components))]
                       (parse-draw-texture dt))
        child-widgets (x/get-elements widget-elem :Widget)
        slots (x/get-elements widget-elem :Slot)
        buttons (x/get-elements widget-elem :Button)
        labels (x/get-elements widget-elem :Label)
        histograms (x/get-elements widget-elem :Histogram)
        properties (when-let [pl (x/get-element widget-elem :PropertyList)]
                     (x/get-elements pl :Property))
        animations (x/get-elements widget-elem :Animation)]
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
          root-widget (x/get-element parsed :Widget)]
      (when-not root-widget
        (throw (ex-info "No Widget element found in XML" {:path xml-path})))
      (let [result (parse-widget root-widget)]
        (log/info "Successfully parsed XML layout:" xml-path)
        result))
    (catch Exception e
      (log/error "Failed to parse XML layout:" xml-path "-"(ex-message e))
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
;; Runtime Widget Loading (formerly cgui-document)
;; ============================================================================

(defn- text-at [node tag default]
  (if-let [child (x/get-element node tag)]
    (or (x/get-text child) default)
    default))

(defn- class-short-name [class-name]
  (last (str/split (str class-name) #"\.")))

(defn- normalize-component-kind [class-name]
  (let [short (class-short-name class-name)
        kebab (-> short
                  (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                  str/lower-case
                  (str/replace #"[_\s]+" "-"))]
    (case kebab
      "transform" :transform
      "drawtexture" :draw-texture
      "draw-texture" :draw-texture
      "textbox" :text-box
      "text-box" :text-box
      "tint" :tint
      "outline" :outline
      "elementlist" :element-list
      "element-list" :element-list
      "progressbar" :progress-bar
      "progress-bar" :progress-bar
      "dragbar" :drag-bar
      "drag-bar" :drag-bar
      nil)))

(defn- parse-color-int [color-node default-color]
  (if color-node
    (let [r (int (parse/parse-float (text-at color-node :red "255") 255))
          g (int (parse/parse-float (text-at color-node :green "255") 255))
          b (int (parse/parse-float (text-at color-node :blue "255") 255))
          a (int (parse/parse-float (text-at color-node :alpha "255") 255))]
      (bit-or (bit-shift-left a 24)
              (bit-shift-left r 16)
              (bit-shift-left g 8)
              b))
    default-color))

(declare build-widget)

(defn- apply-component! [widget component-node]
  (let [class-name (get-in component-node [:attrs :class] "")
        component-kind (normalize-component-kind class-name)]
    (case component-kind
      :transform
      (let [w (parse/parse-float (text-at component-node :width "0") 0)
            h (parse/parse-float (text-at component-node :height "0") 0)
            x-pos (parse/parse-float (text-at component-node :x "0") 0)
            y-pos (parse/parse-float (text-at component-node :y "0") 0)
            scale (parse/parse-float (text-at component-node :scale "1") 1)
            does-draw? (not= false (parse/parse-bool (text-at component-node :doesDraw "true")))
            pivot-x (parse/parse-float (text-at component-node :pivotX "0") 0)
            pivot-y (parse/parse-float (text-at component-node :pivotY "0") 0)
            align-w (some-> (text-at component-node :alignWidth "LEFT") str/upper-case keyword)
            align-h (some-> (text-at component-node :alignHeight "TOP") str/upper-case keyword)
            does-listen (not= false (parse/parse-bool (text-at component-node :doesListenKey "true")))
            z-level (parse/parse-float (text-at component-node :zLevel "0") 0)]
        (cgui/set-size! widget w h)
        (cgui/set-pos! widget x-pos y-pos)
        (cgui/set-scale! widget scale)
        (cgui/set-visible! widget does-draw?)
        (swap! (:metadata widget) assoc
               :transform-meta {:pivot-x pivot-x
                                :pivot-y pivot-y
                                :align-width align-w
                                :align-height align-h
                                :does-listen-key does-listen
                                :z-level z-level}))

      :draw-texture
      (let [texture-node (x/get-element component-node :texture)
            texture-path (when texture-node
                           (when-not (= "true" (get-in texture-node [:attrs :isNull]))
                             (x/normalize-xml-texture (x/get-text texture-node))))
            color (parse-color-int (x/get-element component-node :color) 0xFFFFFFFF)]
        (comp/add-component! widget (comp/draw-texture texture-path color)))

      :text-box
      (let [content (text-at component-node :content "")
            option-node (x/get-element component-node :option)
            color (parse-color-int (x/get-element option-node :color) 0xFFFFFFFF)
            allow-edit? (boolean (parse/parse-bool (text-at component-node :allowEdit "false")))
            does-echo? (boolean (parse/parse-bool (text-at component-node :doesEcho "false")))
            localized? (boolean (parse/parse-bool (text-at component-node :localized "false")))]
        (comp/add-component! widget
                             (comp/text-box :text content
                                            :color color
                                            :masked? does-echo?
                                            :shadow? false
                                            :localized? localized?))
        (when-let [tb (comp/get-textbox-component widget)]
          (comp/set-editable! tb allow-edit?)))

      :tint
      (let [idle-color (parse-color-int (x/get-element component-node :idleColor) 0x99FFFFFF)]
        (comp/add-component! widget (comp/tint idle-color)))

      :outline
      (let [color (parse-color-int (x/get-element component-node :color) 0xFFFFFFFF)
            width (parse/parse-float (text-at component-node :lineWidth "1") 1)]
        (comp/add-component! widget (comp/outline :color color :width width)))

      :element-list
      (let [spacing (parse/parse-float (text-at component-node :spacing "2") 2)]
        (comp/add-component! widget (comp/element-list :spacing spacing)))

      :progress-bar
      (let [dir-str (str/lower-case (text-at component-node :dir "right"))
            direction (keyword dir-str)
            progress (parse/parse-float (text-at component-node :progress "0") 0)
            color (parse-color-int (x/get-element component-node :color) 0xFFFFFFFF)]
        (comp/add-component! widget
                             (comp/progress-bar :direction direction
                                                :progress progress
                                                :color-full color)))

      :drag-bar
      (let [lower (parse/parse-float (text-at component-node :lower "0") 0)
            upper (parse/parse-float (text-at component-node :upper "0") 0)
            height (max 1.0 (- upper lower))]
        (comp/add-component! widget (comp/drag-bar :height height)))

      nil)))

(defn- build-widget [widget-node]
  (let [widget (cgui/create-container :name (get-in widget-node [:attrs :name]))]
    (doseq [component-node (x/get-elements widget-node :Component)]
      (apply-component! widget component-node))
    (doseq [child-node (x/get-elements widget-node :Widget)]
      (cgui/add-widget! widget (build-widget child-node)))
    widget))

(defn read-xml
  "Read XML layout and return root runtime widget tree.

  resource-loc formats accepted:
    \"my_mod:guis/rework/page_inv.xml\"  -> assets/my_mod/guis/rework/page_inv.xml
    \"assets/my_mod/guis/rework/page_inv.xml\""
  [resource-loc]
  (log/info "[XML-PARSER] load-xml START:" resource-loc)
  (let [path (if (str/includes? resource-loc ":")
               (str "assets/" (str/replace resource-loc #":" "/"))
               resource-loc)
        xml-resource (or (io/resource path)
                         (io/resource path (.getClassLoader (class read-xml))))
        _ (when-not xml-resource
            (throw (ex-info (str "XML resource not found: " path)
                            {:resource-loc resource-loc :path path})))
        _ (log/info "[XML-PARSER] XML resource found, parsing...")
        parsed (xml/parse (io/input-stream xml-resource))
        root-widget-node (x/get-element parsed :Widget)]
    (log/info "[XML-PARSER] XML parsed successfully, building widget...")
    (let [result (build-widget root-widget-node)]
      (log/info "[XML-PARSER] Widget built, size:" (cgui/get-size result) "visible:" (cgui/visible? result))
      result)))

(defn get-widget
  "Get named widget from parsed widget tree.
  If root name matches, returns root directly."
  [doc name]
  (when doc
    (if (= name (cgui/get-name doc))
      doc
      (cgui/find-widget doc name))))

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
