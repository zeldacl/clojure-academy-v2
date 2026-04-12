(ns cn.li.ac.gui.tech-ui-common
  "TechUI共享组件库 (参照Scala TechUI.scala)
  
  提供：
  - InventoryPage构建器
  - InfoArea辅助函数（histogram, property, sepline, button）
  - 通用样式和动画
  - Histogram元素构建器"
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]))

;; ============================================================================
;; Constants
;; ============================================================================

(def gui-width 172)
(def gui-height 187)

;; TechUI container screen size delta (AcademyCraft ContainerUI: xSize += 31, ySize += 20).
;; Platform layer (e.g. screen_impl) reads :size-dx/:size-dy from the map returned by create-screen.
(def tech-ui-size-dx 31)
(def tech-ui-size-dy 20)

(defn assoc-tech-ui-screen-size
  "Merge TechUI size deltas into a cgui-screen-container map. Call when create-screen returns a TechUI layout so the platform layer gets :size-dx/:size-dy without repeating constants."
  [m]
  (assoc m :size-dx tech-ui-size-dx :size-dy tech-ui-size-dy))

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
      
      (log/info "TechUI page loaded, widget size:" (cgui/get-size page-widget) "visible:" (cgui/visible? page-widget))
      
      ;; Add breathing effect to all UI elements
      (doseq [widget (cgui/get-draw-list page-widget)]
        (when (str/starts-with? (cgui/get-name widget) "ui_")
          (comp/add-component! widget (comp/breathe-effect))))
      
      ;; Set UI block texture based on name
      (when-let [ui-block (cgui/find-widget page-widget "ui_block")]
        (comp/set-texture! ui-block 
          (modid/asset-path "textures" (str "guis/ui/ui_" name ".png"))))
      
      (log/info "TechUI inventory page created:" name)
      {:id "inv" :window page-widget})
    (catch Exception e
      (log/error "Error creating inventory page:"(ex-message e))
      (log/error "Stack trace:" (.printStackTrace e))
      {:id "inv" :window (cgui/create-container :pos [0 0] :size [gui-width gui-height])})))

;; ============================================================================
;; InfoArea辅助函数 (参照TechUI.ContainerUI.InfoArea)
;; ============================================================================

(def ^:private info-area-key-length 40)
(def ^:private info-area-expect-width 100.0)
(def ^:private info-area-min-height 50.0)

(defn- now-sec []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn- argb-ensure-alpha [color-int]
  (let [c (long (or color-int 0xFFFFFFFF))]
    (if (pos? (bit-and c 0xFF000000))
      (unchecked-int c)
      (unchecked-int (bit-or 0xFF000000 c)))))

(defn- argb-with-alpha [color-int alpha-byte]
  (let [base (long (argb-ensure-alpha color-int))
        a (bit-and (long alpha-byte) 0xFF)]
    (unchecked-int (bit-or (bit-shift-left a 24) (bit-and base 0x00FFFFFF)))))

(defn- info-area-state-atom
  "Return the state atom stored on info-area metadata, creating if absent."
  [info-area]
  (let [m (:metadata info-area)]
    (or (:tech-ui/info-area-state @m)
        (let [a (atom {:elem-y 0.0
                       :elements-count 0
                       :expect-width info-area-expect-width
                       :expect-height info-area-min-height
                       :last-frame-time (now-sec)
                       :blend-start-time (now-sec)
                       :blend-targets []})]
          (swap! m assoc :tech-ui/info-area-state a)
          a))))

(defn widget-subtree-seq
  "Depth-first sequence of `root` and all descendant widgets (shared TechUI helper)."
  [root]
  (when root
    (tree-seq (constantly true) cgui/get-widgets root)))

(defn- register-blend-targets!
  "Scan widget subtree and register any components that should participate in InfoArea fade-in."
  [info-area widget]
  (let [state-a (info-area-state-atom info-area)]
    (swap! state-a
           (fn [st]
             (let [targets
                   (->> (widget-subtree-seq widget)
                        (mapcat (fn [w]
                                  (mapcat
                                    (fn [c]
                                      (when (map? c)
                                        (let [k (:kind c)
                                              s (:state c)]
                                          (cond
                                            (= k :drawtexture)
                                            [{:kind :drawtexture :state s :key :color :base (argb-ensure-alpha (:color @s 0xFFFFFFFF))}]

                                            (= k :textbox)
                                            [{:kind :textbox :state s :key :color :base (argb-ensure-alpha (:color @s 0xFFFFFFFF))}]

                                            (= k :progressbar)
                                            [{:kind :progressbar :state s :key :color-full :base (argb-ensure-alpha (:color-full @s 0xFFFFFFFF))}
                                             {:kind :progressbar :state s :key :color-empty :base (argb-ensure-alpha (:color-empty @s 0xFFFFFFFF))}]

                                            :else
                                            []))))
                                    @(:components w))))
                        vec)]
               (update st :blend-targets into targets))))))

(defn reset-info-area!
  "Clear InfoArea content and reset its internal layout/animation state."
  [info-area]
  (cgui/clear-widgets! info-area)
  (when-let [bg (:tech-ui/info-area-bg @(:metadata info-area))]
    (cgui/add-widget! info-area bg))
  (let [state-a (info-area-state-atom info-area)]
    (reset! state-a {:elem-y 0.0
                     :elements-count 0
                     :expect-width info-area-expect-width
                     :expect-height info-area-min-height
                     :last-frame-time (now-sec)
                     :blend-start-time (now-sec)
                     :blend-targets []}))
  (when-let [bg (:tech-ui/info-area-bg @(:metadata info-area))]
    (register-blend-targets! info-area bg))
  info-area)

(defn- maybe-init-elem-y!
  [info-area y-offset]
  (when (number? y-offset)
    (let [state-a (info-area-state-atom info-area)]
      (swap! state-a
             (fn [st]
               (if (zero? (long (:elements-count st 0)))
                 (assoc st :elem-y (double y-offset))
                 st))))))

(defn- info-area-blank!
  [info-area ht]
  (let [state-a (info-area-state-atom info-area)]
    (swap! state-a update :elem-y (fnil #(+ (double %) (double ht)) 0.0)))
  info-area)

(defn- info-area-element!
  "Stack an element into InfoArea using internal elem-y, update expect-height, and register blend targets."
  [info-area elem]
  (let [state-a (info-area-state-atom info-area)
        [_ew eh] (cgui/get-size elem)
        s (double (or @(:scale elem) 1.0))]
    (swap! state-a
           (fn [st]
             (let [y (double (:elem-y st 10.0))
                   next-y (+ y (* (double eh) s))
                   next-expect (max info-area-min-height (+ next-y 8.0))]
               (cgui/set-pos! elem (first (cgui/get-pos elem)) y)
               (-> st
                   (assoc :elem-y next-y)
                   (update :elements-count (fnil inc 0))
                   (assoc :expect-height next-expect)))))
    (cgui/add-widget! info-area elem)
    (register-blend-targets! info-area elem))
  info-area)

(defn add-histogram
  "Add histogram widget to info area
  
  Args:
  - info-area: Widget - InfoArea容器
  - elements: HistElement序列 [{:label :color :value-fn :desc-fn}]
  - y-offset: Starting Y position
  
  Returns: Updated Y offset"
  [info-area elements y-offset]
  (maybe-init-elem-y! info-area y-offset)
  (let [hist-widget (cgui/create-widget :pos [0 0] :size [210 210])
        _ (comp/add-component! hist-widget
                               (comp/texture (modid/asset-path "textures" "guis/histogram.png") 0 0 210 210))
        _ (cgui/set-scale! hist-widget 0.4)
        _ (cgui/set-z-level! hist-widget 10)]
    
    ;; Add histogram bars
    (doseq [[elem idx] (map vector elements (range))]
      (let [bar-x (+ 56 (* idx 40))
            bar (cgui/create-widget :pos [bar-x 78] :size [16 120])
            progress (comp/progress-bar
                       :direction :vertical
                       :progress 0.0
                       :color-full (:color elem)
                       :color-empty 0x20404040)]
        (comp/add-component! bar progress)
        (events/on-frame bar
          (fn [_]
            (let [value ((:value-fn elem))
                  clamped (Math/max 0.03 (Math/min 1.0 (double value)))]
              (comp/set-progress! progress clamped))))
        (cgui/add-widget! hist-widget bar)))
    
    (info-area-element! info-area hist-widget)
    ;; Keep rows below the histogram to avoid overlap in our scaled layout.
    (info-area-blank! info-area 3)
    
    ;; Add histogram property lines (icon + key/value aligned)
    (doseq [elem elements]
      (let [row (cgui/create-widget :pos [6 0] :size [(- info-area-expect-width 10) 8])
            key-area (cgui/create-widget :pos [4 0] :size [32 8])
            icon (cgui/create-widget :pos [-3 0.5] :size [6 6])
            value-area (cgui/create-widget :pos [info-area-key-length 0] :size [40 8])
            key-box (comp/text-box :text (str (:label elem)) :color 0xFFAAAAAA :scale 0.8)
            value-box (comp/text-box :text (str ((:desc-fn elem))) :color 0xFFFFFFFF :scale 0.8)]
        (comp/add-component! icon (comp/draw-texture nil (:color elem)))
        (comp/add-component! key-area key-box)
        (comp/add-component! value-area value-box)
        (events/on-frame value-area (fn [_] (comp/set-text! value-box ((:desc-fn elem)))))
        (cgui/add-widget! row key-area)
        (cgui/add-widget! row icon)
        (cgui/add-widget! row value-area)
        (info-area-element! info-area row)))
    ;; small gap before next section
    (info-area-blank! info-area 3)
    ;; Return a y-offset compatible with old callers.
    (double (:elem-y @(info-area-state-atom info-area)))))

(defn add-sepline
  "Add separator line
  
  Args:
  - info-area: Widget
  - label: String
  - y-offset: Y position
  
  Returns: Updated Y offset"
  [info-area label y-offset]
  (maybe-init-elem-y! info-area y-offset)
  (info-area-blank! info-area 3)
  (let [sep-widget (cgui/create-widget :pos [3 0] :size [97 8])]
    (comp/add-component! sep-widget
      (comp/text-box
        :text label
        :color 0x99FFFFFF
        :scale 0.6))
    (info-area-element! info-area sep-widget)
    (info-area-blank! info-area 3)
    (double (:elem-y @(info-area-state-atom info-area)))))

(defn add-property
  "Add property field (read-only or editable)
  
  Args:
  - info-area: Widget
  - label: String - property name
  - value: Any - current value (or value-fn for dynamic)
  - y-offset: Y position
  - opts: {:editable? :on-change :masked? :color-change? :content-cell}
  
  Returns: Updated Y offset"
  [info-area label value y-offset & {:keys [editable? on-change masked? color-change? content-cell]
                                      :or {editable? false masked? false color-change? true}}]
  (maybe-init-elem-y! info-area y-offset)
  (let [prop-widget (cgui/create-widget :pos [6 0] :size [(- info-area-expect-width 10) 8])
        key-area (cgui/create-widget :pos [0 0] :size [info-area-key-length 8])
        ;; remaining width for value (roughly)
        value-area (cgui/create-widget :pos [info-area-key-length 0]
                                       :size [(max 1 (- info-area-expect-width info-area-key-length 10)) 8])
        label-box (comp/text-box :text (str label) :color 0xFFAAAAAA :scale 0.8)
        value-text (if (fn? value) (value) (str value))
        idle-color 0xFFFFFFFF
        edit-color 0xFF2180d8
        value-color (if editable? edit-color idle-color)
        value-box (comp/text-box :text value-text :color value-color :scale 0.8 :masked? masked?)]
    
    (comp/add-component! key-area label-box)
    (comp/add-component! value-area value-box)
    (cgui/add-widget! prop-widget key-area)
    (cgui/add-widget! prop-widget value-area)

    (when (instance? clojure.lang.IAtom content-cell)
      (reset! content-cell value-box))
    
    (when editable?
      (comp/set-editable! value-box true)
      ;; Visual brackets around editable value area.
      (let [box (fn [ch x]
                  ;; size 0 so it won't steal focus in hit-test, but still renders text
                  (let [w (cgui/create-widget :pos [x 0] :size [0 0])
                        tb (comp/text-box :text ch :color 0xFFAAAAAA :scale 0.8)]
                    (comp/add-component! w tb)
                    w))
            left (box "[" -4)
            right (box "]" (+ (cgui/get-width value-area) 2))]
        (cgui/add-widget! value-area left)
        (cgui/add-widget! value-area right))
      (when on-change
        (events/on-confirm-input value-box
          (fn [new-val]
            (on-change new-val)
            (when color-change?
              (comp/set-text-color! value-box idle-color))))
        (when color-change?
          (events/on-change-content value-box
            (fn [_]
              (comp/set-text-color! value-box edit-color))))))
    
    (when-not editable?
      (when (fn? value)
        (events/on-frame value-area
          (fn [_]
            (comp/set-text! value-box (value))))))
    
    (info-area-element! info-area prop-widget)
    (double (:elem-y @(info-area-state-atom info-area)))))

(defn add-button
  "Add button
  
  Args:
  - info-area: Widget
  - text: String
  - on-click: (fn [] ...)
  - y-offset: Y position
  
  Returns: Updated Y offset"
  [info-area text on-click y-offset]
  (maybe-init-elem-y! info-area y-offset)
  (let [button-widget (cgui/create-widget :pos [50 0] :size [50 8])
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
    (info-area-element! info-area button-widget)
    (double (:elem-y @(info-area-state-atom info-area)))))

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
  (let [info-area (cgui/create-container :pos [0 0] :size [info-area-expect-width info-area-min-height])
        bg (cgui/create-widget :name "info_area_bg" :pos [0 0] :size [info-area-expect-width info-area-min-height])
        state-a (info-area-state-atom info-area)]
    ;; True TechUI BlendQuad (nine-slice + line overlays), rendered by runtime.
    (comp/add-component! bg (comp/blend-quad :margin 4.0 :color 0x80FFFFFF))
    (cgui/set-z-level! bg -100)
    (cgui/add-widget! info-area bg)
    (swap! (:metadata info-area) assoc :tech-ui/info-area-bg bg)
    (register-blend-targets! info-area bg)

    (events/on-frame info-area
      (fn [_evt]
        (let [t (now-sec)]
          (swap! state-a
                 (fn [{:keys [last-frame-time expect-width expect-height blend-start-time blend-targets] :as st}]
                   (let [dt (min 0.5 (max 0.0 (- t (double (or last-frame-time t)))))
                         move (fn [from to]
                                (let [max-step (* dt 500.0)
                                      delta (- (double to) (double from))
                                      step (min max-step (Math/abs delta))]
                                  (+ (double from) (* step (Math/signum delta)))))
                         [cur-w cur-h] (cgui/get-size info-area)
                         nw (move cur-w expect-width)
                         nh (move cur-h expect-height)
                         ;; fade in over ~0.3s after 0.3s delay (same shape as old TechUI)
                         balpha (-> (/ (- t (double blend-start-time) 0.3) 0.3)
                                    (max 0.0) (min 1.0))
                         a (int (Math/round (* 255.0 balpha)))]
                     (cgui/set-size! info-area nw nh)
                     (cgui/set-size! bg nw nh)
                     (doseq [{:keys [state key base]} blend-targets]
                       (when (and state key)
                         (swap! state assoc key (argb-with-alpha base a))))
                     (assoc st :last-frame-time t)))))))
    info-area))

;; ============================================================================
;; Page helpers and main Tech UI composer
;; ============================================================================

(defn apply-breathe-to-ui!
  "Apply breathe effect to all **direct** child widgets whose name starts with `ui_`."
  [page-widget]
  (doseq [w (cgui/get-draw-list page-widget)]
    (when (clojure.string/starts-with? (cgui/get-name w) "ui_")
      (comp/add-component! w (comp/breathe-effect)))))

(defn apply-breathe-to-ui-descendants!
  "Apply breathe to every descendant named `ui_*` (e.g. nested `ui_left` / `ui_right` in `page_developer.xml`)."
  [page-root]
  (doseq [w (widget-subtree-seq page-root)]
    (when (str/starts-with? (cgui/get-name w) "ui_")
      (comp/add-component! w (comp/breathe-effect)))))

(defn- tech-ui-opts-map?
  "True if trailing map is TechUI composer options (`:on-tab-change`, `:main-size`, etc.)."
  [m]
  (and (map? m)
       (or (contains? m :on-tab-change)
           (contains? m :main-size))))

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
  Optional trailing opts map:
  - `:on-tab-change` — (fn [page-id])
  - `:main-size` — [width height] for `tech_ui_main` (defaults to `gui-width` × `gui-height`).
  Returns a map {:id \"tech\" :window main-widget :pages {id page-map} :current atom}
  "
  [& args]
  (let [opts (when (and (seq args) (tech-ui-opts-map? (last args)))
               (last args))
        pages (if opts (drop-last args) args)
        pages (if (and (= 1 (count pages)) (sequential? (first pages))) (first pages) pages)
        on-tab-change (:on-tab-change opts)
        main-size (vec (or (:main-size opts) [gui-width gui-height]))
        mw (double (nth main-size 0 gui-width))
        mh (double (nth main-size 1 gui-height))
        main (cgui/create-widget :name "tech_ui_main" :pos [0 0] :size [mw mh])
        current (atom (when (seq pages) (:id (first pages))))
        pages-map (into {} (map (fn [p] [(:id p) p]) pages))]

    ;; Add pages and page buttons
    (doseq [[p idx] (map vector pages (range))]
      (let [pw (:window p)]
        (when-not pw
          (log/error (str "create-tech-ui: page " (:id p) " has nil :window — skipping")))
        (when pw
          (cgui/set-pos! pw 0 0)
          (cgui/set-visible! pw false)
          (cgui/add-widget! main pw)

          (let [btn (page-button (:id p) idx current pages pw nil on-tab-change)]
            (cgui/add-widget! main btn)))))

    ;; show first page by default
    (when-let [first-page (first pages)]
      (when-let [fw (:window first-page)]
        (cgui/set-visible! fw true)))

    ;; apply breathe effect to inventory-like pages
    (doseq [p pages]
      (when-let [w (:window p)]
        (apply-breathe-to-ui! w)))

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
