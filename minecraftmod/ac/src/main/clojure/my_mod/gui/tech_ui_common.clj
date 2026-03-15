(ns my-mod.gui.tech-ui-common
  "TechUI共享组件库 (参照Scala TechUI.scala)
  
  提供：
  - InventoryPage构建器
  - InfoArea辅助函数（histogram, property, sepline, button）
  - 通用样式和动画
  - Histogram元素构建器"
  (:require [clojure.string :as str]
            [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.gui.cgui-document :as cgui-doc]
            [my-mod.util.log :as log]
            [my-mod.config.modid :as modid]))

;; ============================================================================
;; Constants
;; ============================================================================

(def gui-width 172)
(def gui-height 187)

;; ============================================================================
;; InventoryPage - 共享库存页面
;; ============================================================================

(defn create-inventory-page
  "Create shared inventory page (参照Scala InventoryPage)
  
  Args:
  - name: String - 'matrix' or 'node' - determines UI texture
  
  Returns: Page map with {:id :window}"
  [name]
  (try
        (let [page-xml (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_inv.xml"))
          page-widget (cgui-doc/get-widget page-xml "main")]
      
      ;; Add breathing effect to all UI elements
      (doseq [widget (cgui/get-draw-list page-widget)]
        (when (str/starts-with? (cgui/get-name widget) "ui_")
          (comp/add-component! widget (comp/breathe-effect))))
      
      ;; Set UI block texture based on name
      (when-let [ui-block (cgui/find-widget page-widget "ui_block")]
        (comp/set-texture! ui-block 
          (modid/asset-path "textures" (str "guis/ui/ui_" name ".png"))))
      
      {:id "inv" :window page-widget})
    (catch Exception e
      (log/error "Error creating inventory page:" (.getMessage e))
      {:id "inv" :window (cgui/create-container :pos [0 0] :size [gui-width gui-height])})))

;; ============================================================================
;; InfoArea辅助函数 (参照TechUI.ContainerUI.InfoArea)
;; ============================================================================

(defn add-histogram
  "Add histogram widget to info area
  
  Args:
  - info-area: Widget - InfoArea容器
  - elements: HistElement序列 [{:label :color :value-fn :desc-fn}]
  - y-offset: Starting Y position
  
  Returns: Updated Y offset"
  [info-area elements y-offset]
  (let [hist-widget (cgui/create-widget :pos [0 y-offset] :size [210 210])
    _ (comp/add-component! hist-widget
      (comp/texture (modid/asset-path "textures" "guis/histogram.png") 0 0 210 210))
        _ (cgui/set-scale! hist-widget 0.4)]
    
    ;; Add histogram bars
    (doseq [[elem idx] (map vector elements (range))]
      (let [bar-x (+ 56 (* idx 40))
            bar (cgui/create-widget :pos [bar-x 78] :size [16 120])
            progress (comp/progress-bar
                       :direction :vertical
                       :progress 0.0
                       :color (:color elem))]
        (comp/add-component! bar progress)
        (events/on-frame bar
          (fn [_]
            (let [value ((:value-fn elem))
                  clamped (Math/max 0.03 (Math/min 1.0 value))]
              (comp/set-progress! progress clamped))))
        (cgui/add-widget! hist-widget bar)))
    
    (cgui/add-widget! info-area hist-widget)
    
    ;; Add histogram property labels below
    (let [prop-start-y (+ y-offset 90)]
      (reduce
        (fn [y elem]
          (let [prop-widget (cgui/create-widget :pos [6 y] :size [88 8])
                icon (cgui/create-widget :pos [-3 0.5] :size [6 6])
                label-box (comp/text-box :text (:label elem) :color 0xFFAAAAAA :scale 0.8)
                value-box (comp/text-box :text "" :color 0xFFFFFFFF :scale 0.8)]
            (comp/add-component! icon (comp/draw-texture nil (:color elem)))
            (comp/add-component! prop-widget label-box)
            (comp/add-component! prop-widget value-box)
            (events/on-frame prop-widget
              (fn [_]
                (comp/set-text! value-box ((:desc-fn elem)))))
            (cgui/add-widget! prop-widget icon)
            (cgui/add-widget! info-area prop-widget)
            (+ y 8)))
        prop-start-y
        elements))))

(defn add-sepline
  "Add separator line
  
  Args:
  - info-area: Widget
  - label: String
  - y-offset: Y position
  
  Returns: Updated Y offset"
  [info-area label y-offset]
  (let [sep-widget (cgui/create-widget :pos [3 y-offset] :size [97 8])]
    (comp/add-component! sep-widget
      (comp/text-box
        :text label
        :color 0x99FFFFFF
        :scale 0.6))
    (cgui/add-widget! info-area sep-widget)
    (+ y-offset 11)))

(defn add-property
  "Add property field (read-only or editable)
  
  Args:
  - info-area: Widget
  - label: String - property name
  - value: Any - current value (or value-fn for dynamic)
  - y-offset: Y position
  - opts: {:editable? :on-change :masked? :color-change?}
  
  Returns: Updated Y offset"
  [info-area label value y-offset & {:keys [editable? on-change masked? color-change?]
                                      :or {editable? false masked? false color-change? true}}]
  (let [prop-widget (cgui/create-widget :pos [6 y-offset] :size [88 8])
        label-box (comp/text-box :text label :color 0xFFAAAAAA :scale 0.8)
        value-text (if (fn? value) (value) (str value))
        value-color (if editable? 0xFF2180d8 0xFFFFFFFF)
        value-box (comp/text-box :text value-text :color value-color :scale 0.8 :masked? masked?)]
    
    (comp/add-component! prop-widget label-box)
    (comp/add-component! prop-widget value-box)
    
    (when editable?
      (comp/set-editable! value-box true)
      (when on-change
        (events/on-confirm-input value-box
          (fn [new-val]
            (on-change new-val)
            (when color-change?
              (comp/set-text-color! value-box 0xFFFFFFFF))))
        (when color-change?
          (events/on-change-content value-box
            (fn [_]
              (comp/set-text-color! value-box 0xFF2180d8))))))
    
    (when-not editable?
      (when (fn? value)
        (events/on-frame prop-widget
          (fn [_]
            (comp/set-text! value-box (value))))))
    
    (cgui/add-widget! info-area prop-widget)
    (+ y-offset 10)))

(defn add-button
  "Add button
  
  Args:
  - info-area: Widget
  - text: String
  - on-click: (fn [] ...)
  - y-offset: Y position
  
  Returns: Updated Y offset"
  [info-area text on-click y-offset]
  (let [button-widget (cgui/create-widget :pos [50 y-offset] :size [50 8])
        text-box (comp/text-box :text text :color 0xFFFFFFFF :scale 0.9)]
    (comp/add-component! button-widget text-box)
    (events/on-left-click button-widget on-click)
    (events/on-frame button-widget
      (fn [evt]
        (let [lum (if (:hovering evt) 1.0 0.8)
              color-val (int (* lum 255))]
          (comp/set-text-color! text-box
            (bit-or 0xFF000000
                   (bit-shift-left color-val 16)
                   (bit-shift-left color-val 8)
                   color-val)))))
    (cgui/add-widget! info-area button-widget)
    (+ y-offset 11)))

;; ============================================================================
;; Histogram元素构建器 (参照TechUI.histEnergy等)
;; ============================================================================

(defn hist-energy
  "Create energy histogram element"
  [energy-fn max-energy]
  {:label "Energy"
   :color 0xFF25c4ff
   :value-fn (fn [] (/ (double (energy-fn)) (double max-energy)))
   :desc-fn (fn [] (str (int (energy-fn)) " IF"))})

(defn hist-capacity
  "Create capacity histogram element"
  [load-fn max-capacity]
  {:label "Capacity"
   :color 0xFFff6c00
   :value-fn (fn [] (/ (double (load-fn)) (double max-capacity)))
   :desc-fn (fn [] (str (load-fn) "/" max-capacity))})

(defn hist-buffer
  "Create buffer histogram element"
  [buffer-fn max-buffer]
  {:label "Buffer"
   :color 0xFF25f7ff
   :value-fn (fn [] (/ (double (buffer-fn)) (double max-buffer)))
   :desc-fn (fn [] (str (int (buffer-fn)) " IF"))})

;; ============================================================================
;; InfoArea容器创建
;; ============================================================================

(defn create-info-area
  "Create InfoArea container with BlendQuad background
  
  Returns: InfoArea widget"
  []
  (let [info-area (cgui/create-container :pos [0 0] :size [100 50])]
    ;; TODO: Add BlendQuad component
    ;; (comp/add-component! info-area (comp/blend-quad :margin 4))
    info-area))

;; ============================================================================
;; Page helpers and main Tech UI composer
;; ============================================================================

(defn apply-breathe-to-ui!
  "Apply breathe effect to all child widgets whose name starts with `ui_`."
  [page-widget]
  (doseq [w (cgui/get-draw-list page-widget)]
    (when (clojure.string/starts-with? (cgui/get-name w) "ui_")
      (comp/add-component! w (comp/breathe-effect)))))

(defn page-button
  "Create a page-select button using icon texture and hover animation.

  Args:
  - id: page id string
  - idx: index (used for vertical position)
  - current-atom: atom holding current page id
  - pages: sequence of page maps
  - target-window: the window widget for this page
  - on-click: optional fn to call after switching (no args)
  - on-tab-change: optional (fn [page-id]) called when tab is switched, for syncing to server

  Returns: widget"
  [id idx current-atom pages target-window & [on-click on-tab-change]]
  (let [y (* idx 22)
        click-fn (fn []
                   (log/info "Switching to page:" id)
                   (doseq [q pages]
                     (when-let [w (:window q)]
                       (cgui/set-visible! w false)))
                   (when target-window
                     (cgui/set-visible! target-window true))
                   (reset! current-atom id)
                   (when on-tab-change (on-tab-change id))
                   (when on-click (on-click)))
        icon-path (modid/asset-path "textures" (str "guis/icons/icon_" id ".png"))
        page-xml (cgui-doc/read-xml (modid/namespaced-path "guis/rework/pageselect.xml"))
        btn (cgui-doc/get-widget page-xml "main")
        ;btn (comp/button :text "" :x -20 :y y :width 20 :height 20 :on-click click-fn)
        ]
    ;; prepare click handler: hide all pages, show target, update current
    ;; add draw texture component for icon 
    ;(comp/add-component! btn (comp/draw-texture icon-path 0xFFFFFFFF))
    (comp/set-texture! btn icon-path)
    ;; scale and position 
    (cgui/set-w-align! btn :left)
    (cgui/set-h-align! btn :top)
    (cgui/set-scale! btn 0.7)
    (cgui/set-pos! btn -20 y)

    ;; frame handler: adjust alpha and tint based on hover/current
    (events/on-frame btn
                     (fn [evt]
                       (let [hovering (boolean (:hovering evt))
                             cur @current-atom
                             a1 (if (or hovering (= cur id)) 1.0 0.8)
                             a2 (if (= cur id) 1.0 0.8)
                             alpha-byte (int (* a1 255))
                             rgb-byte (int (* a2 255))
                             color (bit-or (bit-shift-left (bit-and alpha-byte 0xFF) 24)
                                           (bit-shift-left (bit-and rgb-byte 0xFF) 16)
                                           (bit-shift-left (bit-and rgb-byte 0xFF) 8)
                                           (bit-and rgb-byte 0xFF))]
                         (when-let [dt (comp/get-drawtexture-component btn)]
                           (swap! (:state dt) assoc :color color)))))
    (events/on-left-click btn (events/make-click-handler click-fn))
    (log/debug "Created page button for" id "at y=" (keys @(:metadata btn)) "with widget:" (get @(:metadata btn) :transform-meta :align-height))
    btn))

(defn create-tech-ui
  "Create a tech UI composed from given pages.

  Each page should be a map {:id \"inv\" :window widget}.
  If the last argument is a map with :on-tab-change key, it is opts; :on-tab-change is (fn [page-id]).
  Returns a map {:id \"tech\" :window main-widget :pages {id page-map} :current atom}
  "
  [& args]
  (let [opts (when (and (seq args) (map? (last args)) (contains? (last args) :on-tab-change))
               (last args))
        pages (if opts (drop-last args) args)
        pages (if (and (= 1 (count pages)) (sequential? (first pages))) (first pages) pages)
        on-tab-change (:on-tab-change opts)
        main (cgui/create-widget :name "tech_ui_main" :pos [0 0] :size [gui-width gui-height])
        current (atom (when (seq pages) (:id (first pages))))
        pages-map (into {} (map (fn [p] [(:id p) p]) pages))]

    ;; Add pages and page buttons
    (doseq [[p idx] (map vector pages (range))]
      (let [pw (:window p)]
        (cgui/set-pos! pw 0 0)
        (cgui/set-visible! pw false)
        (cgui/add-widget! main pw)

        (let [btn (page-button (:id p) idx current pages pw nil on-tab-change)]
          (cgui/add-widget! main btn))))

    ;; show first page by default
    (when-let [first-page (first pages)]
      (cgui/set-visible! (:window first-page) true))

    ;; apply breathe effect to inventory-like pages
    (doseq [p pages]
      (apply-breathe-to-ui! (:window p)))

    {:id "tech"
     :window main
     :pages pages-map
     :current current
     ;; :show-page-fn (fn [id]
     ;;                 (when-let [p (get pages-map id)]
     ;;                   (doseq [[_ pm] pages-map]
     ;;                     (cgui/set-visible! (:window pm) false))
     ;;                   (cgui/set-visible! (:window p) true)
     ;;                   (reset! current id)
     ;;                   p))
     }))
