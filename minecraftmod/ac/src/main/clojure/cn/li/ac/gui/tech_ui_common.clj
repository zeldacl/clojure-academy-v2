(ns cn.li.ac.gui.tech-ui-common
  "TechUI共享组件库 (参照Scala TechUI.scala)
  
  提供：
  - InventoryPage构建器
  - InfoArea辅助函数（histogram, property, sepline, button）
  - 通用样式和动画
  - Histogram元素构建器"
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.container-state :as container-state]
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

(declare create-tech-ui create-info-area reset-info-area!)

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
      
      (log/info "TechUI page loaded, widget size:" (cgui-core/get-size page-widget) "visible:" (cgui-core/visible? page-widget))
      
      ;; Add breathing effect to all UI elements
      (doseq [widget (cgui-core/get-draw-list page-widget)]
        (when (str/starts-with? (cgui-core/get-name widget) "ui_")
          (comp/add-component! widget (comp/breathe-effect))))
      
      ;; Set UI block texture based on name
      (when-let [ui-block (cgui-core/find-widget page-widget "ui_block")]
        (comp/set-texture! ui-block 
          (modid/asset-path "textures" (str "guis/ui/ui_" name ".png"))))
      
      (log/info "TechUI inventory page created:" name)
      {:id "inv" :window page-widget})
    (catch Exception e
      (log/error "Error creating inventory page:"(ex-message e))
      (log/error "Stack trace:" (.printStackTrace e))
      {:id "inv" :window (cgui-core/create-container :pos [0 0] :size [gui-width gui-height])})))

(defn create-rework-page
  "Create a TechUI page from an XML resource under assets/my_mod/guis/rework."
  ([resource-path]
   (create-rework-page "inv" resource-path))
  ([page-id resource-path]
   (let [doc (cgui-doc/read-xml (if (str/starts-with? resource-path "assets/")
                                  resource-path
                                  (modid/namespaced-path resource-path)))
         window (cgui-doc/get-widget doc "main")]
     {:id page-id :window window})))

(defn- page-window-by-id
  [pages page-id]
  (some (fn [page]
          (when (= (:id page) page-id)
            (:window page)))
        pages))

(defn assemble-tech-ui-root
  "Assemble common TechUI tabs, tab sync, optional bindings and InfoArea.

  Options:
  - :pages vector of {:id string :window widget}
  - :container AC GUI container map
  - :container-id menu container id used by tab sync
  - :minecraft-container optional menu object used to derive :container-id
  - :bind! optional fn receiving {:root :tech :pages :page-window}
  - :build-info-area! optional fn receiving the created info-area widget
  - :info-anchor-page-id page id used for InfoArea positioning, default first page
  Returns {:root widget :tech tech-ui-map :current atom :pages pages}.
  "
  [{:keys [pages container container-id minecraft-container bind! build-info-area! info-anchor-page-id]
    :or {info-anchor-page-id nil}}]
  (let [pages (vec pages)
        container-id (or container-id
                         (when minecraft-container
                           (container-state/get-menu-container-id minecraft-container)))
        tech (apply create-tech-ui pages)
        _ (tabbed-gui/attach-tab-sync! pages tech container container-id)
        root (:window tech)
        anchor-id (or info-anchor-page-id (:id (first pages)))
        page-window (fn [page-id] (page-window-by-id pages page-id))
        ctx {:root root
             :tech tech
             :pages pages
             :page-window page-window}]
    (when bind!
      (bind! ctx))
    (when build-info-area!
      (let [info-area (create-info-area)
            anchor-window (or (page-window anchor-id) (:window (first pages)))]
        (when anchor-window
          (cgui-core/set-position! info-area (+ (cgui-core/get-width anchor-window) 7) 5))
        (reset-info-area! info-area)
        (build-info-area! info-area)
        (cgui-core/add-widget! root info-area)))
    {:root root
     :tech tech
     :current (:current tech)
     :pages pages}))

(defn create-tech-screen-container
  "Create a CGui screen container from common TechUI screen options."
  [{:keys [minecraft-container] :as opts}]
  (let [assembled (assemble-tech-ui-root
                    (assoc opts :container-id (container-state/get-menu-container-id minecraft-container)))
        base (cgui-screen/create-cgui-screen-container (:root assembled) minecraft-container)]
    (assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current assembled)))))

(defn create-tech-screen-from-root
  "Wrap an already assembled TechUI root as a CGui screen container."
  [root current minecraft-container]
  (let [base (cgui-screen/create-cgui-screen-container root minecraft-container)]
    (cond-> (assoc-tech-ui-screen-size base)
      current (assoc :current-tab-atom current))))

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
    (tree-seq (constantly true) cgui-core/get-widgets root)))

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
  (cgui-core/clear-widgets! info-area)
  (when-let [bg (:tech-ui/info-area-bg @(:metadata info-area))]
    (cgui-core/add-widget! info-area bg))
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
        [_ew eh] (cgui-core/get-size elem)
        s (double (or @(:scale elem) 1.0))]
    (swap! state-a
           (fn [st]
             (let [y (double (:elem-y st 10.0))
                   next-y (+ y (* (double eh) s))
                   next-expect (max info-area-min-height (+ next-y 8.0))]
               (cgui-core/set-pos! elem (first (cgui-core/get-pos elem)) y)
               (-> st
                   (assoc :elem-y next-y)
                   (update :elements-count (fnil inc 0))
                   (assoc :expect-height next-expect)))))
    (cgui-core/add-widget! info-area elem)
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
  (let [hist-widget (cgui-core/create-widget :pos [0 0] :size [210 210])
        _ (comp/add-component! hist-widget
                               (comp/texture (modid/asset-path "textures" "guis/histogram.png") 0 0 210 210))
        _ (cgui-core/set-scale! hist-widget 0.4)
        _ (cgui-core/set-z-level! hist-widget 10)]
    
    ;; Add histogram bars
    (doseq [[elem idx] (map vector elements (range))]
      (let [bar-x (+ 56 (* idx 40))
            bar (cgui-core/create-widget :pos [bar-x 78] :size [16 120])
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
        (cgui-core/add-widget! hist-widget bar)))
    
    (info-area-element! info-area hist-widget)
    ;; Keep rows below the histogram to avoid overlap in our scaled layout.
    (info-area-blank! info-area 3)
    
    ;; Add histogram property lines (icon + key/value aligned)
    (doseq [elem elements]
      (let [row (cgui-core/create-widget :pos [6 0] :size [(- info-area-expect-width 10) 8])
            key-area (cgui-core/create-widget :pos [4 0] :size [32 8])
            icon (cgui-core/create-widget :pos [-3 0.5] :size [6 6])
            value-area (cgui-core/create-widget :pos [info-area-key-length 0] :size [40 8])
            key-box (comp/text-box :text (str (:label elem)) :color 0xFFAAAAAA :scale 0.8)
            value-box (comp/text-box :text (str ((:desc-fn elem))) :color 0xFFFFFFFF :scale 0.8)]
        (comp/add-component! icon (comp/draw-texture nil (:color elem)))
        (comp/add-component! key-area key-box)
        (comp/add-component! value-area value-box)
        (events/on-frame value-area (fn [_] (comp/set-text! value-box ((:desc-fn elem)))))
        (cgui-core/add-widget! row key-area)
        (cgui-core/add-widget! row icon)
        (cgui-core/add-widget! row value-area)
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
  (let [sep-widget (cgui-core/create-widget :pos [3 0] :size [97 8])]
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
  (let [prop-widget (cgui-core/create-widget :pos [6 0] :size [(- info-area-expect-width 10) 8])
        key-area (cgui-core/create-widget :pos [0 0] :size [info-area-key-length 8])
        ;; remaining width for value (roughly)
        value-area (cgui-core/create-widget :pos [info-area-key-length 0]
                                       :size [(max 1 (- info-area-expect-width info-area-key-length 10)) 8])
        label-box (comp/text-box :text (str label) :color 0xFFAAAAAA :scale 0.8)
        value-text (if (fn? value) (value) (str value))
        idle-color 0xFFFFFFFF
        edit-color 0xFF2180d8
        value-color (if editable? edit-color idle-color)
        value-box-spec (comp/text-box :text value-text :color value-color :scale 0.8 :masked? masked?)]
    
    (comp/add-component! key-area label-box)
    (comp/add-component! value-area value-box-spec)
    (cgui-core/add-widget! prop-widget key-area)
    (cgui-core/add-widget! prop-widget value-area)
    (let [value-box (comp/get-textbox-component value-area)]

      (when (instance? clojure.lang.IAtom content-cell)
        (reset! content-cell value-box)
        (log/info "add-property: content-cell populated for" label "editable?" editable?))
    
      (when editable?
        (comp/set-editable! value-box true)
        ;; Visual brackets around editable value area.
        (let [box (fn [ch x]
                    ;; size 0 so it won't steal focus in hit-test, but still renders text
                    (let [w (cgui-core/create-widget :pos [x 0] :size [0 0])
                          tb (comp/text-box :text ch :color 0xFFAAAAAA :scale 0.8)]
                      (comp/add-component! w tb)
                      w))
              left (box "[" -4)
              right (box "]" (+ (cgui-core/get-width value-area) 2))]
          (cgui-core/add-widget! value-area left)
          (cgui-core/add-widget! value-area right))
        (when on-change
          (log/info "add-property: registering on-confirm-input for" label)
          (events/on-confirm-input value-box
            (fn [new-val]
              (log/info "add-property: on-confirm-input fired for" label "new-val:" new-val)
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
              (comp/set-text! value-box (value)))))))
    
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
  (let [button-widget (cgui-core/create-widget :name (str "btn_" text) :pos [50 0] :size [50 8])
        text-box (comp/text-box :text text :color 0xFFFFFFFF :scale 0.9)]
    (comp/add-component! button-widget text-box)
    (events/on-left-click button-widget (events/make-click-handler on-click))
    (log/info "add-button: registered left-click on" text "size:" (cgui-core/get-size button-widget) "pos:" (cgui-core/get-pos button-widget))
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
    (log/info "add-button:" text "added, final pos:" (cgui-core/get-pos button-widget) "info-area children:" (count (cgui-core/get-widgets info-area)))
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
  (let [info-area (cgui-core/create-container :pos [0 0] :size [info-area-expect-width info-area-min-height])
        bg (cgui-core/create-widget :name "info_area_bg" :pos [0 0] :size [info-area-expect-width info-area-min-height])
        state-a (info-area-state-atom info-area)]
    ;; True TechUI BlendQuad (nine-slice + line overlays), rendered by runtime.
    (comp/add-component! bg (comp/blend-quad :margin 4.0 :color 0x80FFFFFF))
    (cgui-core/set-z-level! bg -100)
    (cgui-core/add-widget! info-area bg)
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
                         [cur-w cur-h] (cgui-core/get-size info-area)
                         nw (move cur-w expect-width)
                         nh (move cur-h expect-height)
                         ;; fade in over ~0.3s after 0.3s delay (same shape as old TechUI)
                         balpha (-> (/ (- t (double blend-start-time) 0.3) 0.3)
                                    (max 0.0) (min 1.0))
                         a (int (Math/round (* 255.0 balpha)))]
                     (cgui-core/set-size! info-area nw nh)
                     (cgui-core/set-size! bg nw nh)
                     (doseq [{:keys [state key base]} blend-targets]
                       (when (and state key)
                         (swap! state assoc key (argb-with-alpha base a))))
                     (assoc st :last-frame-time t)))))))
    info-area))

;; ============================================================================
;; Page helpers and main Tech UI composer
;; ============================================================================

(defn apply-breathe-to-ui!
  "Apply breathe effect to all **direct* child widgets whose name starts with `ui_`."
  [page-widget]
  (doseq [w (cgui-core/get-draw-list page-widget)]
    (when (clojure.string/starts-with? (cgui-core/get-name w) "ui_")
      (comp/add-component! w (comp/breathe-effect)))))

(defn apply-breathe-to-ui-descendants!
  "Apply breathe to every descendant named `ui_*` (e.g. nested `ui_left` / `ui_right` in `page_developer.xml`)."
  [page-root]
  (doseq [w (widget-subtree-seq page-root)]
    (when (str/starts-with? (cgui-core/get-name w) "ui_")
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
                       (cgui-core/set-visible! w false)))
                   (when target-window
                     (cgui-core/set-visible! target-window true))
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
    (cgui-core/set-w-align! btn :left)
    (cgui-core/set-h-align! btn :top)
    (cgui-core/set-scale! btn 0.7)
    (cgui-core/set-pos! btn -20 y)

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
        main (cgui-core/create-widget :name "tech_ui_main" :pos [0 0] :size [mw mh])
        ;; Initialize CGUI runtime state on the root widget so that
        ;; gain-focus!, key-input!, and focused-editable-textbox? can
        ;; find the focus atom. Mirrors create-cgui in cgui_screen.clj.
        _ (when-not (:cgui-focus @(:metadata main))
            (swap! (:metadata main) assoc
                   :cgui-focus (atom nil)
                   :dragging-node (atom nil)
                   :last-drag-time (atom 0)
                   :last-start-time (atom 0)))
        current (atom (when (seq pages) (:id (first pages))))
        pages-map (into {} (map (fn [p] [(:id p) p]) pages))]

    ;; Add pages and page buttons
    (doseq [[p idx] (map vector pages (range))]
      (let [pw (:window p)]
        (when-not pw
          (log/error (str "create-tech-ui: page " (:id p) " has nil :window — skipping")))
        (when pw
          (cgui-core/set-pos! pw 0 0)
          (cgui-core/set-visible! pw false)
          (cgui-core/add-widget! main pw)

          (let [btn (page-button (:id p) idx current pages pw nil on-tab-change)]
            (cgui-core/add-widget! main btn)))))

    ;; show first page by default
    (when-let [first-page (first pages)]
      (when-let [fw (:window first-page)]
        (cgui-core/set-visible! fw true)))

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
     ;;                     (cgui-core/set-visible! (:window pm) false))
     ;;                   (cgui-core/set-visible! (:window p) true)
     ;;                   (reset! current id)
     ;;                   p))
     }))
