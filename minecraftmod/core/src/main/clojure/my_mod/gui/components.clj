(ns my-mod.gui.components
  "Pure Clojure component API inspired by Java references."
  (:require [clojure.string :as str]
            [my-mod.gui.cgui :as cgui]
            [my-mod.gui.events :as events]
            [my-mod.config.modid :as modid]))

(defn- ensure-resource-location
  [texture-path]
  (if (string? texture-path)
    (if (re-find #":" texture-path)
      (let [[namespace path] (str/split texture-path #":" 2)]
        (modid/resource-location namespace path))
      (modid/resource-location texture-path))
    texture-path))

(defn- component-kind [component]
  (or (:kind component) (::kind component) :unknown))

(defn- component-state [component]
  (or (:state component) (atom {})))

(defn- make-component [kind initial-state]
  {:kind kind :state (atom initial-state)})

(defn add-component!
  "Add a component to a widget"
  [widget component]
  (let [native (if (and (map? component) (::kind component))
                 (create-native-component component)
                 component)
        comp-with-owner (if (and (map? native) (:state native))
                          (do
                            (swap! (:state native) assoc :owner-widget widget)
                            native)
                          native)]
    (cgui/add-widget-component! widget comp-with-owner)
    widget))

(defn remove-component!
  "Remove a component from widget"
  [widget component]
  (cgui/remove-widget-component! widget component)
  widget)

(defn get-component
  "Get component of specific type from widget (keyword or class-like value)."
  [widget component-class]
  (if (keyword? component-class)
    (cgui/get-widget-component widget component-class)
    (let [kind (-> (str component-class) str/lower-case keyword)]
      (cgui/get-widget-component widget kind))))

(defn get-textbox-component [widget]
  (get-component widget :textbox))

(defn get-drawtexture-component [widget]
  (get-component widget :drawtexture))

(defn get-tint-component [widget]
  (get-component widget :tint))

(defn texture
  ([texture-path]
   {::kind :drawtexture :texture texture-path})
  ([texture-path u v w h]
   {::kind :drawtexture :texture texture-path :uv [u v w h]}))

(defn draw-texture
  ([texture-path color]
   {::kind :drawtexture :texture texture-path :color color})
  ([texture-path]
   {::kind :drawtexture :texture texture-path}))

(defn- create-native-component
  [spec]
  (let [kind (::kind spec)]
    (case kind
      :drawtexture (make-component :drawtexture
                                   {:texture (some-> (:texture spec) ensure-resource-location)
                                    :uv (:uv spec)
                                    :color (:color spec 0xFFFFFFFF)
                                    :z-level (:z-level spec 0.0)
                                    :write-depth true})
      :textbox (make-component :textbox
                               {:text (str (:text spec ""))
                                :color (:color spec 0xFFFFFF)
                                :scale (:scale spec 1.0)
                                :shadow? (:shadow? spec true)
                                :masked? (:masked? spec false)
                                :editable? (:editable? spec false)})
      :progressbar (make-component :progressbar
                                   {:direction (:direction spec :horizontal)
                                    :progress (double (:progress spec 0.0))
                                    :color-full (:color-full spec 0x00FF00)
                                    :color-empty (:color-empty spec 0x404040)})
      :outline (make-component :outline
                               {:color (:color spec 0xFFFFFF)
                                :width (:width spec 1.0)})
      :tint (make-component :tint
                            {:color (:color spec 0xFFFFFFFF)
                             :alpha 255})
      :draggable (make-component :draggable {:enabled? true})
      :dragbar (make-component :dragbar {:text (:text spec "") :height (:height spec 12)})
      :elementlist (make-component :elementlist
                                   {:spacing (:spacing spec 2)
                                    :progress 0
                                    :items []})
      :transform (make-component :transform
                                 {:translate (:translate spec [0 0])
                                  :scale (:scale spec 1.0)
                                  :rotate (:rotate spec 0.0)})
      :breathe-effect (make-component :breathe-effect {:phase 0.0 :speed 1.0})
      (throw (ex-info "Unknown component kind" {:kind kind})))))

(defn set-texture!
  "Set texture on DrawTexture component or widget drawtexture component"
  [target texture-path]
  (let [component (if (and (map? target) (= :drawtexture (component-kind target)))
                    target
                    (get-drawtexture-component target))]
    (when component
      (swap! (component-state component) assoc :texture (ensure-resource-location texture-path)))
    component))

(defn set-uv!
  "Set texture UV coordinates"
  [draw-tex u v]
  (swap! (component-state draw-tex) assoc :uv [u v nil nil])
  draw-tex)

(defn render-texture-region
  [widget texture-path x y w h u0 v0 u1 v1]
  (let [dt (or (get-drawtexture-component widget)
               (let [new-dt (create-native-component (texture texture-path))]
                 (add-component! widget new-dt)
                 new-dt))]
    (set-texture! dt texture-path)
    (swap! (component-state dt) assoc :uv [u0 v0 (- u1 u0) (- v1 v0)])
    (cgui/set-pos! widget x y)
    (cgui/set-size! widget w h)
    widget))

(defn text-box
  [& {:keys [text color scale shadow? masked?]
      :or {text "" color 0xFFFFFF scale 1.0 shadow? true masked? false}}]
  {::kind :textbox
   :text text
   :color color
   :scale scale
   :shadow? shadow?
   :masked? masked?})

(defn set-text! [text-box text]
  (swap! (component-state text-box) assoc :text (str text))
  text-box)

(defn get-text [text-box]
  (str (:text @(component-state text-box) "")))

(defn set-text-color! [text-box color]
  (swap! (component-state text-box) assoc :color color)
  text-box)

(defn set-editable! [text-box editable?]
  (swap! (component-state text-box) assoc :editable? (boolean editable?))
  text-box)

(defn progress-bar
  [& {:keys [direction progress color-full color-empty]
      :or {direction :horizontal progress 0.0
           color-full 0x00FF00 color-empty 0x404040}}]
  {::kind :progressbar
   :direction direction
   :progress progress
   :color-full color-full
   :color-empty color-empty})

(defn set-progress! [pb value]
  (swap! (component-state pb) assoc :progress (double (max 0.0 (min 1.0 value))))
  pb)

(defn get-progress [pb]
  (double (:progress @(component-state pb) 0.0)))

(defn transform
  [& {:keys [translate scale rotate]
      :or {translate [0 0] scale 1.0 rotate 0.0}}]
  {::kind :transform :translate translate :scale scale :rotate rotate})

(defn set-translate! [trans x y]
  (swap! (component-state trans) assoc :translate [x y])
  trans)

(defn set-component-scale! [trans scale]
  (swap! (component-state trans) assoc :scale scale)
  trans)

(defn set-rotate! [trans degrees]
  (swap! (component-state trans) assoc :rotate degrees)
  trans)

(defn outline
  [& {:keys [color width]
      :or {color 0xFFFFFF width 1.0}}]
  {::kind :outline :color color :width width})

(defn set-outline-color! [outline-comp color]
  (swap! (component-state outline-comp) assoc :color color)
  outline-comp)

(defn set-outline-width! [outline-comp width]
  (swap! (component-state outline-comp) assoc :width width)
  outline-comp)

(defn tint
  ([] {::kind :tint})
  ([color] {::kind :tint :color color}))

(defn set-tint! [tint-comp color]
  (swap! (component-state tint-comp) assoc :color color)
  tint-comp)

(defn set-alpha! [tint-comp alpha]
  (swap! (component-state tint-comp) assoc :alpha alpha)
  tint-comp)

(defn draggable []
  {::kind :draggable})

(defn set-draggable! [drag enabled?]
  (swap! (component-state drag) assoc :enabled? (boolean enabled?))
  drag)

(defn drag-bar
  [& {:keys [text height]
      :or {text "" height 12}}]
  {::kind :dragbar :text text :height height})

(defn set-drag-bar-title! [drag-bar-comp text]
  (swap! (component-state drag-bar-comp) assoc :text text)
  drag-bar-comp)

(defn element-list
  [& {:keys [spacing]
      :or {spacing 2}}]
  {::kind :elementlist :spacing spacing})

(defn list-add! [elem-list widget]
  (swap! (component-state elem-list) update :items (fnil conj []) widget)
  elem-list)

(defn list-remove! [elem-list widget]
  (swap! (component-state elem-list) update :items
         (fn [xs] (vec (remove #(= (:id %) (:id widget)) (or xs [])))))
  elem-list)

(defn list-clear! [elem-list]
  (swap! (component-state elem-list) assoc :items [] :progress 0)
  elem-list)

(defn list-progress-next! [elem-list]
  (swap! (component-state elem-list) update :progress (fnil inc 0))
  elem-list)

(defn list-progress-last! [elem-list]
  (swap! (component-state elem-list) update :progress (fnil #(max 0 (dec %)) 0))
  elem-list)

(defn breathe-effect []
  {::kind :breathe-effect})

(defn text-field
  [& {:keys [text placeholder]
      :or {text "" placeholder ""}}]
  (text-box :text (if (empty? text) placeholder text)))

(defn set-placeholder! [text-box-comp placeholder]
  (when (empty? (str (get-text text-box-comp)))
    (set-text! text-box-comp placeholder))
  text-box-comp)

(defn button
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
  [& {:keys [label x y width height color value-fn max-fn direction]
      :or {label "" x 0 y 0 width 60 height 40 color 0x00FF00
           value-fn (constantly 0) max-fn (constantly 1)
           direction :vertical}}]
  (let [panel (cgui/create-widget :pos [x y] :size [width height])
        bar (create-native-component
              {::kind :progressbar
               :direction direction
               :progress 0.0
               :color-full color
               :color-empty 0x404040})
        label-box (create-native-component
                    {::kind :textbox :text label :color 0xFFFFFF :scale 0.6 :shadow? true})]
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
  [& {:keys [label x y width label-color value-color]
      :or {label "" x 0 y 0 width 70 label-color 0xAAAAAA value-color 0xFFFFFF}}]
  (let [widget (cgui/create-widget :pos [x y] :size [width 12])
        label-box (create-native-component
                    {::kind :textbox :text (str label ": ") :color label-color :scale 0.7 :shadow? true})
        value-box (create-native-component
                    {::kind :textbox :text "" :color value-color :scale 0.7 :shadow? true})]
    (add-component! widget label-box)
    (add-component! widget value-box)
    widget))

(defn with-components
  [widget components]
  (doseq [comp components]
    (add-component! widget comp))
  widget)

(defmacro components->
  [widget & forms]
  `(-> ~widget ~@forms))
