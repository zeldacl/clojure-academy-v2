(ns cn.li.mcmod.gui.cgui-document
  "Pure Clojure XML document loader for CGUI-like layouts."
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.util.parse :as parse]
            [cn.li.mcmod.util.xml :as x]
            [cn.li.mcmod.gui.components :as comp]))

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
  "Read XML layout and return root widget tree.

  resource-loc formats accepted:
    \"my_mod:guis/rework/page_inv.xml\"  → assets/my_mod/guis/rework/page_inv.xml
    \"assets/my_mod/guis/rework/page_inv.xml\"  (passed through as-is)"
  [resource-loc]
  (let [path (if (str/includes? resource-loc ":")
               ;; Callers include the .xml extension already; do NOT append it again.
               (str "assets/" (str/replace resource-loc #":" "/"))
               resource-loc)
        ;; Try thread-context classloader first (works in most environments).
        ;; Fall back to the DynamicClassLoader that loaded this namespace so
        ;; Forge's module classloader hierarchy is also searched.
        xml-resource (or (io/resource path)
                         (io/resource path (.getClassLoader (class read-xml))))
        _ (when-not xml-resource
            (throw (ex-info (str "XML resource not found: " path)
                            {:resource-loc resource-loc :path path})))
        parsed (xml/parse (io/input-stream xml-resource))
        root-widget-node (x/get-element parsed :Widget)]
    (build-widget root-widget-node)))

(defn get-widget
  "Get named widget from parsed widget tree.
  If the root widget's own name matches, return it directly.
  Otherwise delegates to find-widget which searches children."
  [doc name]
  (when doc
    (if (= name (cgui/get-name doc))
      doc
      (cgui/find-widget doc name))))
