(ns my-mod.gui.cgui-document
  "Pure Clojure XML document loader for CGUI-like layouts."
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]))

(defn- parse-float [s default]
  (try (Float/parseFloat (str/trim (str s))) (catch Exception _ default)))

(defn- parse-bool [s default]
  (if (nil? s)
    default
    (= "true" (str/lower-case (str/trim (str s))))))

(defn- child-elements [node tag]
  (filter #(and (map? %) (= (:tag %) tag)) (:content node)))

(defn- first-child [node tag]
  (first (child-elements node tag)))

(defn- node-text [node]
  (some->> (:content node) (filter string?) first str/trim))

(defn- text-at [node tag default]
  (if-let [child (first-child node tag)]
    (or (node-text child) default)
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
    (let [r (int (parse-float (text-at color-node :red "255") 255))
          g (int (parse-float (text-at color-node :green "255") 255))
          b (int (parse-float (text-at color-node :blue "255") 255))
          a (int (parse-float (text-at color-node :alpha "255") 255))]
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
      (let [w (parse-float (text-at component-node :width "0") 0)
            h (parse-float (text-at component-node :height "0") 0)
            x (parse-float (text-at component-node :x "0") 0)
            y (parse-float (text-at component-node :y "0") 0)
            scale (parse-float (text-at component-node :scale "1") 1)
            does-draw? (parse-bool (text-at component-node :doesDraw "true") true)]
        (cgui/set-size! widget w h)
        (cgui/set-pos! widget x y)
        (cgui/set-scale! widget scale)
        (cgui/set-visible! widget does-draw?))

      :draw-texture
      (let [texture-node (first-child component-node :texture)
            texture-path (when texture-node
                           (when-not (= "true" (get-in texture-node [:attrs :isNull]))
                             (node-text texture-node)))
            color (parse-color-int (first-child component-node :color) 0xFFFFFFFF)]
        (comp/add-component! widget (comp/draw-texture texture-path color)))

      :text-box
      (let [content (text-at component-node :content "")
            option-node (first-child component-node :option)
            color (parse-color-int (first-child option-node :color)
                                   0xFFFFFFFF)
            allow-edit? (parse-bool (text-at component-node :allowEdit "false") false)
            does-echo? (parse-bool (text-at component-node :doesEcho "false") false)]
        (comp/add-component! widget
          (comp/text-box :text content :color color :masked? does-echo? :shadow? false))
        (when-let [tb (comp/get-textbox-component widget)]
          (comp/set-editable! tb allow-edit?)))

      :tint
      (let [idle-color (parse-color-int (first-child component-node :idleColor) 0x99FFFFFF)]
        (comp/add-component! widget (comp/tint idle-color)))

      :outline
      (let [color (parse-color-int (first-child component-node :color) 0xFFFFFFFF)
            width (parse-float (text-at component-node :lineWidth "1") 1)]
        (comp/add-component! widget (comp/outline :color color :width width)))

      :element-list
      (let [spacing (parse-float (text-at component-node :spacing "2") 2)]
        (comp/add-component! widget (comp/element-list :spacing spacing)))

      :progress-bar
      (let [dir-str (str/lower-case (text-at component-node :dir "right"))
            direction (keyword dir-str)
            progress (parse-float (text-at component-node :progress "0") 0)
            color (parse-color-int (first-child component-node :color) 0xFFFFFFFF)]
        (comp/add-component! widget
          (comp/progress-bar :direction direction
                             :progress progress
                             :color-full color)))

      :drag-bar
      (let [lower (parse-float (text-at component-node :lower "0") 0)
            upper (parse-float (text-at component-node :upper "0") 0)
            height (max 1.0 (- upper lower))]
        (comp/add-component! widget (comp/drag-bar :height height)))

      nil)))

(defn- build-widget [widget-node]
  (let [widget (cgui/create-container :name (get-in widget-node [:attrs :name]))]
    (doseq [component-node (child-elements widget-node :Component)]
      (apply-component! widget component-node))
    (doseq [child-node (child-elements widget-node :Widget)]
      (cgui/add-widget! widget (build-widget child-node)))
    widget))

(defn read-xml
  "Read XML layout and return root widget tree."
  [resource-loc]
  (let [path (if (str/includes? resource-loc ":")
               (str "assets/" (str/replace resource-loc #":" "/") ".xml")
               resource-loc)
        xml-resource (io/resource path)
        parsed (xml/parse-str (slurp xml-resource))
        root-widget-node (first-child parsed :Widget)]
    (build-widget root-widget-node)))

(defn get-widget
  "Get named widget from parsed widget tree"
  [doc name]
  (cgui/find-widget doc name))
