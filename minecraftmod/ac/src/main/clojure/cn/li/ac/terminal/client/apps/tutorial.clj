(ns cn.li.ac.terminal.client.apps.tutorial
  "CLIENT-ONLY: Tutorial GUI — port of original AcademyCraft GuiTutorial.

  Three-panel layout:
    Left   — tutorial entry list (13 entries), dimmed for unlearned
    Center — scrollable markdown content with drag-bar scroll
    Right  — preview area + brief text + tag icon + navigation

  First-open behavior (matching original AC):
    - 4 logos fade in with staggered timing (logo3:0.1s, logo2:0.65s, logo1:1.3s, logo0:1.75s)
    - Left/center/right panels hidden until animation completes (~2.0s)
    - First-open flag tracked per-session; subsequent opens skip animation

  Scroll: content widgets inside a container whose y-position is offset by
  scroll-y, controlled by drag on the scroll-thumb widget."
  (:require [cn.li.ac.tutorial.content :as tut-content]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.ac.tutorial.markdown-renderer :as mr]
            [cn.li.ac.tutorial.client.preview :as preview]
            [cn.li.ac.tutorial.client.state :as client-state]
            [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

;; --- Layout constants (matching original tutorial.xml) ---

(def gw 427.0) (def gh 240.0)  ;; frame: 427×240
(def panel-h 220.5)              ;; leftPart/rightPart/centerPart height from tutorial.xml
(def lw 85.0)  (def lx 7.0)   (def lix 6.6) (def liy 7.0)
(def liw 72.0) (def eh 12.0)   ;; entry height 12px matches original
(def cx (+ lx lw)) (def cw 172.0)
;; cox = 5.0: text widget x=2 + 3px internal pad (matches upstream glTranslated(3,3-delta,0))
(def cox 5.0)  (def cow 160.0)  ;; upstream text widget: 160×210.5
(def coy 0.0)  (def coh (- panel-h coy))  ;; content visible height
;; Upstream showWindow at x = rightPart.x + rightPart.width - showWindow.width
;; = 92 + 332 - 158.5 = 265.5
(def rx 265.5) (def rw 158.5)
(def rix 6.0)  (def riw (- rw (* 2 rix)))

;; Right panel sub-regions (from tutorial.xml)
(def show-window-h 136.0)
(def right-window-h 82.0)

;; Scroll bar dimensions (matching tutorial.xml scroll_1 / scroll_2)
(def scroll-track-w 9.5)
(def scroll-track-h (- panel-h 4.0))  ;; 216.5
(def scroll-thumb-h 53.0)
(def scroll-thumb-min-y 2.0)
(def scroll-thumb-max-y 165.0)

;; Dimming color — matches original Colors.fromFloatMono(0.6f) = 0.6,0.6,0.6
(def unlearned-color 0xFF999999)
(def learned-color 0xFFFFFFFF)

;; --- Texture helper ---

(defn- gui-tex [name]
  (modid/asset-path "textures/guis" name))

;; --- First-open flag ---
;; Per-player persistent via server-side TutorialData (model/mark-first-open-done!).
;; Client cache synced through client-state.

;; --- Content cache ---
;; Lazy cache: [tut-id lang misaka-id] → {:segs [...] :total-h N}
;; Caches the expensive markdown parse; widget creation from segments is cheap.
;; Key includes misaka-id since !(misakaname) tag rendering depends on it.

(def ^:private content-cache (atom {}))

(defn- cached-render-content! [content-ctr tut-id lang content-str misaka-id]
  (let [cache-key [tut-id lang (str misaka-id)]
        cached (get @content-cache cache-key)]
    (if cached
      ;; Rebuild widgets from cached segments (fast — no markdown parsing)
      (do
        (cgui-core/clear-widgets! content-ctr)
        (doseq [[i seg] (map-indexed vector (:segs cached))]
          (let [image? (= (:type seg) :image)
                h (if image? (or (:img-h seg) 100.0) mr/line-height)
                y (:y-offset seg 0.0)
                w (cgui-core/create-widget :pos [0.0 y] :size [cow h])]
            (if image?
              (comp/add-component! w (comp/draw-texture (:texture-path seg)))
              (let [{:keys [text font-size color bold?]} seg]
                (comp/add-component! w (comp/text-box :text text :font-size font-size
                                                      :color color :font (when bold? :ac-bold)))))
            (cgui-core/set-name! w (str "ct-" i))
            (cgui-core/add-widget! content-ctr w)))
        (:total-h cached))
      ;; Parse + cache + render
      (let [segs (mr/render-segments content-str misaka-id)
            total-h (loop [sg segs y 0.0 n 0]
                      (if (seq sg)
                        (let [seg (first sg)
                              image? (= (:type seg) :image)
                              h (if image? (or (:img-h seg) 100.0) mr/line-height)]
                          (recur (rest sg) (+ y h) (inc n)))
                        y))]
        (cgui-core/clear-widgets! content-ctr)
        (doseq [[i seg] (map-indexed vector segs)]
          (let [image? (= (:type seg) :image)
                h (if image? (or (:img-h seg) 100.0) mr/line-height)
                y (:y-offset seg 0.0)
                w (cgui-core/create-widget :pos [0.0 y] :size [cow h])]
            (if image?
              (comp/add-component! w (comp/draw-texture (:texture-path seg)))
              (let [{:keys [text font-size color bold?]} seg]
                (comp/add-component! w (comp/text-box :text text :font-size font-size
                                                      :color color :font (when bold? :ac-bold)))))
            (cgui-core/set-name! w (str "ct-" i))
            (cgui-core/add-widget! content-ctr w)))
        (swap! content-cache assoc cache-key {:segs segs :total-h total-h})
        total-h))))

;; --- Content helpers ---

(defn- clear-content! [content-ctr]
  (cgui-core/clear-widgets! content-ctr))

(defn- render-content! [content-ctr content-str misaka-id]
  (clear-content! content-ctr)
  (let [segs (mr/render-segments content-str misaka-id)]
    (loop [sg segs y 0.0 n 0]
      (if (seq sg)
        (let [seg (first sg)
              image? (= (:type seg) :image)
              h (if image? (or (:img-h seg) 100.0) mr/line-height)
              w (cgui-core/create-widget :pos [0.0 y] :size [cow h])]
          (if image?
            (comp/add-component! w (comp/draw-texture (:texture-path seg)))
            (let [{:keys [text font-size color bold?]} seg]
              (comp/add-component! w (comp/text-box :text text :font-size font-size
                                                    :color color :font (when bold? :ac-bold)))))
          (cgui-core/set-name! w (str "ct-" n))
          (cgui-core/add-widget! content-ctr w)
          (recur (rest sg) (+ y h) (inc n)))
        y))))

(defn- reposition-content! [content-ctr scroll-y]
  (cgui-core/set-pos! content-ctr (+ cx cox) (- coy @scroll-y)))

(defn- clamp-scroll! [scroll-y max-scroll]
  (let [clamped (max 0.0 (min (double @scroll-y) (double max-scroll)))]
    (when (not= @scroll-y clamped)
      (reset! scroll-y clamped))))

;; --- Preview refresh ---

(defn- refresh-preview! [root pvs & {:keys [preview-item preview-type]}]
  ;; Update 3D preview atoms for bridge rendering
  (when-let [view (preview/current-sub-view @pvs)]
    (when preview-type
      (reset! preview-type (or (:type view) :icon)))
    (when preview-item
      (let [vtype (:type view)
            data (case vtype
                   (:recipe :crafting-grid) {:recipe-kind (name (:recipe-kind view))
                                              :item-id (:item-id view)}
                   (or (:item-id view) (:texture view) (:block-id view)))]
        (reset! preview-item data))))
  ;; Clear and rebuild preview widget
  (when-let [area (cgui-core/find-widget root "preview-area")]
    (when-let [old (cgui-core/find-widget area "current-preview")]
      (cgui-core/remove-widget! area old))
    (when-let [view (preview/current-sub-view @pvs)]
      (when-let [pw (preview/build-preview-widget view)]
        (cgui-core/set-name! pw "current-preview")
        (cgui-core/add-widget! area pw))))
  ;; Update rightWindow text
  (when-let [bw (cgui-core/find-widget root "brief-text")]
    (when-let [tb (comp/get-textbox-component bw)]
      (comp/set-text! tb (preview/display-text pvs))))
  ;; Update tag-area buttons (one per ViewGroup)
  (when-let [ta (cgui-core/find-widget root "tag-area")]
    ;; Remove old tag buttons
    (doseq [nm ["tag-0" "tag-1" "tag-2" "tag-3" "tag-4"
                "tag-5" "tag-6" "tag-7" "tag-8" "tag-9"]]
      (when-let [w (cgui-core/find-widget ta nm)]
        (cgui-core/remove-widget! ta w)))
    (let [{:keys [view-groups group-index]} @pvs
          sz 18.0
          step (- sz 1.0)]
      (doseq [[idx vg] (map-indexed vector (or view-groups []))]
        (let [tag-w (cgui-core/create-widget :pos [(* idx step) 0] :size [sz sz])]
          (comp/add-component! tag-w
            (comp/draw-texture (preview/tag-textures (:tag vg) (preview/tag-textures :view))))
          (comp/add-component! tag-w
            (comp/tint (if (= idx group-index) 0xFFFFFFFF 0xB3FFFFFF)))
          (events/on-left-click tag-w
            (fn [_]
              (preview/switch-view-group! pvs idx)
              (refresh-preview! root pvs)))
          (cgui-core/set-name! tag-w (str "tag-" idx))
          (cgui-core/add-widget! ta tag-w)))))
  ;; Show/hide nav buttons based on sub-view count in current ViewGroup
  (let [vg (preview/current-view-group @pvs)
        cnt (count (or (:sub-views vg) []))]
    (doseq [nm ["btn-left" "btn-right"]]
      (when-let [btn (cgui-core/find-widget root nm)]
        (cgui-core/set-visible! btn (> cnt 1))))))

;; --- First-open animation ---

(defn- logo-fade-alpha [elapsed-ms start-delay-ms duration-ms]
  (let [t (max 0.0 (min 1.0 (/ (- elapsed-ms start-delay-ms) duration-ms)))]
    (int (* 255.0 t t (- 3.0 (* 2.0 t))))))

(defn- apply-logo-alpha! [root logo-name alpha]
  "Set the draw-texture component's alpha channel to `alpha` (0-255).
  Preserves the RGB channels (0x00FFFFFF mask)."
  (when-let [lw (cgui-core/find-widget root logo-name)]
    (when-let [dt (comp/get-drawtexture-component lw)]
      ;; dt is already an atom; swap! directly on it
      (swap! dt assoc :color
             (unchecked-int (bit-or (bit-shift-left alpha 24) 0x00FFFFFF))))))

(defn- setup-logo-fade-out!
  "Fade out logos over duration-ms, matching upstream blend(reverse)."
  [root fade-start-ms logo-names duration-ms]
  (let [done? (atom false)]
    (events/on-frame root
      (fn [_]
        (when (and (not @done?) @fade-start-ms)
          (let [elapsed (- (System/currentTimeMillis) @fade-start-ms)
                t (max 0.0 (min 1.0 (/ elapsed (double duration-ms))))
                alpha (- 255 (int (* 255.0 t)))]
            (doseq [nm logo-names]
              (apply-logo-alpha! root nm alpha))
            (when (>= elapsed duration-ms)
              (doseq [nm logo-names]
                (when-let [lw (cgui-core/find-widget root nm)]
                  (cgui-core/set-visible! lw false)))
              (reset! done? true))))))))

(defn- setup-first-open-animation!
  "Register a frame handler matching original GuiTutorial first-open animation:
  - 4 logos fade in with staggered timing
  - logo3 moves vertically (y=63→-36, 0.7s→1.1s)
  - Glow lines do two-phase animation from logo1 center (1.3s+0.4s offset)
  - Only left-panel revealed at end; center/right wait for first entry click"
  [root anim-start]
  (let [logo-timings [["logo3" 100 300]   ;; start 0.10s, duration 0.30s
                      ["logo2" 650 300]    ;; start 0.65s, duration 0.30s
                      ["logo1" 1300 300]   ;; start 1.30s, duration 0.30s
                      ["logo0" 1750 300]]  ;; start 1.75s, duration 0.30s
        ln 500.0 ln2 300.0 cl 50.0
        glow-h 5.0
        glow-frame-offset-ms 400
        logo1-x (- (/ gw 2) 112.375)
        logo1-w 224.75
        logo1-center-x (+ logo1-x (/ logo1-w 2))
        done? (atom false)]
    (events/on-frame root
      (fn [_]
        (when (and (not @done?) @anim-start)
          (let [elapsed (- (System/currentTimeMillis) @anim-start)]
            ;; Staggered logo fade-in
            (doseq [[logo-name start-ms dur-ms] logo-timings]
              (apply-logo-alpha! root logo-name (logo-fade-alpha elapsed start-ms dur-ms)))
            ;; logo3 y-movement: y=63→-36, start 700ms, duration 400ms
            ;; blendy: y0=63 → y1=-36, moving UP in screen space
            ;; screen-y = gh/2 + (y offset relative to center)
            (when-let [l3 (cgui-core/find-widget root "logo3")]
              (let [y-t (max 0.0 (min 1.0 (/ (- elapsed 700.0) 400.0)))
                    l3y (+ 63.0 (* y-t (- -36.0 63.0)))]
                (cgui-core/set-pos! l3 (- (/ gw 2) 18.625) (+ (/ gh 2) l3y))))
            ;; Glow line animation: dt = elapsed - glow-frame-offset-ms
            (let [dt (- elapsed glow-frame-offset-ms)
                  b1 300.0 b2 200.0]
              (when (>= dt 0)
                (if (< dt b1)
                  ;; Phase 1: grow outward 0→500
                  (let [len (* ln (/ dt b1))]
                    (when (> len cl)
                      (when-let [gr (cgui-core/find-widget root "glow-right")]
                        (cgui-core/set-pos! gr (- logo1-center-x cl) (second (cgui-core/get-pos gr)))
                        (cgui-core/set-size! gr (- len cl) glow-h)
                        (cgui-core/set-visible! gr true))
                      (when-let [gl (cgui-core/find-widget root "glow-left")]
                        (cgui-core/set-pos! gl (- logo1-center-x len) (second (cgui-core/get-pos gl)))
                        (cgui-core/set-size! gl (- len cl) glow-h)
                        (cgui-core/set-visible! gl true))))
                  ;; Phase 2: contract
                  (let [ldt (min (- dt b1) b2)
                        len2 (+ (* (- ln cl cl) (- 1.0 (/ ldt b2))) cl)]
                    (when-let [gr (cgui-core/find-widget root "glow-right")]
                      (cgui-core/set-pos! gr (- logo1-center-x (- ln len2)) (second (cgui-core/get-pos gr)))
                      (cgui-core/set-size! gr (- ln (- ln len2)) glow-h))
                    (when-let [gl (cgui-core/find-widget root "glow-left")]
                      (cgui-core/set-pos! gl (- logo1-center-x ln) (second (cgui-core/get-pos gl)))
                      (cgui-core/set-size! gl (- ln (- ln len2)) glow-h))))))
            ;; listArea visible when dt > 2.0s (matching original)
            (when (>= elapsed 2400)
              ;; Reveal only left-panel (listArea); center/right wait for first click
              (when-let [lp (cgui-core/find-widget root "left-panel")]
                (cgui-core/set-visible! lp true))
              ;; Hide logos
              (doseq [nm ["logo0" "logo1" "logo2" "logo3"]]
                (when-let [lw (cgui-core/find-widget root nm)]
                  (cgui-core/set-visible! lw false)))
              ;; Hide glow lines
              (doseq [nm ["glow-right" "glow-left"]]
                (when-let [gw (cgui-core/find-widget root nm)]
                  (cgui-core/set-visible! gw false)))
              ;; Persist first-open-done to server
              (net-client/send-to-server (tut-msg/msg-id :tutorial/mark-first-open-done) {})
              (client-state/apply-sync! {:first-open? false})
              (reset! done? true))))))))

;; --- Brief markdown rendering ---

(defn- render-brief-markdown! [brief-widget brief-str misaka-id]
  "Render tutorial brief text as markdown segments in the brief widget."
  (cgui-core/clear-widgets! brief-widget)
  (let [brief-segs (mr/render-segments brief-str misaka-id 130.0)]
    (loop [sg brief-segs y 0.0 n 0]
      (when (seq sg)
        (let [seg (first sg)
              w (cgui-core/create-widget :pos [0.0 y] :size [riw mr/line-height])]
          (comp/add-component! w
            (comp/text-box :text (:text seg) :font-size (:font-size seg)
                           :color (:color seg) :font (when (:bold? seg) :ac-bold)))
          (cgui-core/set-name! w (str "brief-" n))
          (cgui-core/add-widget! brief-widget w)
          (recur (rest sg) (+ y mr/line-height) (inc n)))))))

;; --- Scroll helpers ---

(defn- reset-scroll! [scroll-y scroll-progress thumb-widget max-scroll content-ctr]
  "Reset scroll position to top and reposition thumb."
  (reset! scroll-y 0.0)
  (reset! scroll-progress 0.0)
  (when-let [thumb @thumb-widget]
    (cgui-core/set-pos! thumb (- cw scroll-track-w) scroll-thumb-min-y))
  (when (pos? @max-scroll)
    (reset! max-scroll 0.0))
  (reposition-content! content-ctr scroll-y))

;; --- Widget builders ---

(defn- build-background! [root]
  "Add dimmed background overlay matching vanilla GuiScreen."
  (let [bg (cgui-core/create-widget :pos [0 0] :size [gw gh])]
    (comp/add-component! bg (comp/tint 0xC0101010))
    (cgui-core/add-widget! root bg)))

(declare make-entry-click-handler)

(defn- build-left-panel!
  "Build left sidebar with tutorial entry list.
  Groups entries by activation status matching upstream GuiTutorial._build():
  learned (white) entries first, then unlearned (gray) entries.
  Uses client-state cache for condition-activated tutorials, falling back
  to default-installed? when client-state is not yet ready."
  [root entries lang first-open? content-ctr ui player-uuid]
  (let [lp (cgui-core/create-widget :pos [lx 0] :size [lw panel-h])
        ;; Determine activation per-entry using client-state when available
        ready? (client-state/ready?)
        is-active? (fn [tut]
                     (or (:default-installed? tut)
                         (and ready?
                           (client-state/is-activated? player-uuid (:id tut)))))
        ;; Split into activated (white) and unactivated (gray), preserving
        ;; registration order within each group (matching upstream groupByLearned)
        {:keys [active inactive]} (reduce (fn [acc tut]
                                            (if (is-active? tut)
                                              (update acc :active conj tut)
                                              (update acc :inactive conj tut)))
                                          {:active [] :inactive []}
                                          entries)
        grouped (concat active inactive)]
    (comp/add-component! lp (comp/draw-texture (gui-tex "window_tutorial_left.png")))
    (doseq [[idx tut] (map-indexed vector grouped)]
      (let [y (+ liy (* idx eh))
            active? (is-active? tut)
            title (or (:title (tut-content/load-tutorial-content lang (:id tut)))
                      (name (:id tut)))
            ew (cgui-core/create-widget :pos [lix y] :size [liw eh])]
        (comp/add-component! ew
          (comp/text-box :text title :font-size 10.0
                         :color (if active? learned-color unlearned-color)
                         :align :left :height-align :center))
        (events/on-left-click ew
          (make-entry-click-handler root tut player-uuid lang content-ctr ui))
        (cgui-core/add-widget! lp ew)))
    (cgui-core/set-name! lp "left-panel")
    (when first-open? (cgui-core/set-visible! lp false))
    (cgui-core/add-widget! root lp)))

(defn- make-entry-click-handler
  "Return a click handler closure for a tutorial entry widget.
  Handles first-click transition, content loading, scroll reset,
  preview refresh, title and brief rendering.
  Re-checks activation from client-state on each click (matches upstream
  GuiTutorial which reads tut.isActivated(player) at click time)."
  [root tut player-uuid lang content-ctr ui]
  (let [{:keys [current-tut-id scroll-y max-scroll scroll-progress
                pvs preview-item preview-type anim-start]} ui]
    (fn [_]
      (when (not= @current-tut-id (:id tut))
        (let [active? (or (:default-installed? tut)
                          (try (client-state/is-activated? player-uuid (:id tut))
                               (catch Throwable _ false)))]
          ;; First-click transition: hide logos + glow, show center/right panels
          (when (nil? @current-tut-id)
            (setup-logo-fade-out! root (atom (System/currentTimeMillis))
                                 ["logo0" "logo1" "logo2" "logo3"] 300)
            (doseq [nm ["glow-right" "glow-left"]]
              (when-let [w (cgui-core/find-widget root nm)]
                (cgui-core/set-visible! w false)))
            (let [cp (cgui-core/find-widget root "center-panel")
                  rp (cgui-core/find-widget root "right-panel")]
              (when cp (cgui-core/set-visible! cp active?))
              (when rp (cgui-core/set-visible! rp true))))
          (reset! current-tut-id (:id tut))
          (let [cd (tut-content/load-tutorial-content lang (:id tut))]
            ;; Center panel visibility follows tut.isActivated(player)
            (when-let [cp (cgui-core/find-widget root "center-panel")]
              (cgui-core/set-visible! cp active?))
            ;; Always reset scroll on tutorial switch (matching upstream)
            (reset! scroll-y 0.0)
            (reset! scroll-progress 0.0)
            (when-let [thumb (cgui-core/find-widget root "scroll-thumb")]
              (cgui-core/set-pos! thumb (- cw scroll-track-w) scroll-thumb-min-y))
            (when active?
              (let [misaka-id (client-state/get-misaka-id player-uuid)
                    total-h (cached-render-content! content-ctr (:id tut) lang
                                                    (:content cd) misaka-id)]
                ;; +10px bottom overshoot matches upstream ht+10
                (reset! max-scroll (max 0.0 (+ (- total-h coh) 10.0)))))
            (reposition-content! content-ctr scroll-y)
          (reset! pvs (preview/create-preview-state (:id tut)))
          (refresh-preview! root pvs
                           :preview-item preview-item
                           :preview-type preview-type)
          ;; Update title text in rightWindow
          (when-let [tw (cgui-core/find-widget root "title-text")]
            (when-let [tb (comp/get-textbox-component tw)]
              (comp/set-text! tb (or (:title cd) ""))))
          ;; Render brief as markdown (matching upstream info.getBrief().render())
          (when-let [bw (cgui-core/find-widget root "brief-text")]
            (render-brief-markdown! bw (:brief cd)
                                   (client-state/get-misaka-id player-uuid)))))))))

(defn- build-center-panel!
  "Build center panel containing scrollable content area and scroll bar.
  Scroll track and thumb are children of center-panel (hidden together)."
  [root content-ctr first-open? ui]
  (let [{:keys [scroll-y max-scroll scroll-progress]} ui
        cp (cgui-core/create-widget :pos [cx 0] :size [cw panel-h])
        _ (swap! (:metadata cp) assoc :clip-children? true)
        track-x (- cw scroll-track-w)
        thumb-travel (- scroll-thumb-max-y scroll-thumb-min-y)
        thumb-x (- cw scroll-track-w)]
    (cgui-core/add-widget! cp content-ctr)
    ;; Scroll track
    (let [track (cgui-core/create-widget
                 :pos [track-x 2.0] :size [scroll-track-w scroll-track-h])]
      (comp/add-component! track (comp/draw-texture (gui-tex "button/widget_scroll_1.png")))
      (cgui-core/set-name! track "scroll-track")
      (cgui-core/add-widget! cp track))
    ;; Scroll thumb
    (let [thumb (cgui-core/create-widget
                 :pos [thumb-x scroll-thumb-min-y] :size [scroll-track-w scroll-thumb-h])]
      (comp/add-component! thumb (comp/draw-texture (gui-tex "button/widget_scroll_2.png")))
      (comp/add-component! thumb (comp/draggable))
      (cgui-core/set-name! thumb "scroll-thumb")
      (events/on-drag thumb
        (fn [evt]
          (when (pos? @max-scroll)
            (let [scroll-delta (* (/ (double (.dy evt)) thumb-travel) @max-scroll)]
              (swap! scroll-y + scroll-delta)
              (clamp-scroll! scroll-y @max-scroll)
              (let [progress (/ @scroll-y @max-scroll)
                    thumb-y (+ scroll-thumb-min-y (* progress thumb-travel))]
                (cgui-core/set-pos! thumb thumb-x thumb-y)
                (reset! scroll-progress progress)
                (reposition-content! content-ctr scroll-y))))))
      (events/on-mouse-scroll thumb
        (fn [evt]
          (when (pos? @max-scroll)
            (let [new-y (+ @scroll-y (* (:delta-y evt) 10.0))]
              (reset! scroll-y (max 0.0 (min @max-scroll new-y)))
              (let [progress (/ @scroll-y @max-scroll)
                    thumb-y (+ scroll-thumb-min-y (* progress thumb-travel))]
                (cgui-core/set-pos! thumb thumb-x thumb-y)
                (reposition-content! content-ctr scroll-y))))))
      (cgui-core/add-widget! cp thumb))
    (cgui-core/set-name! cp "center-panel")
    (when first-open? (cgui-core/set-visible! cp false))
    (cgui-core/add-widget! root cp)))

(defn- build-right-panel!
  "Build right panel: showWindow (preview area + tag buttons + nav arrows)
  and rightWindow (title + brief text area)."
  [root pvs preview-item preview-type first-open?]
  (let [rp (cgui-core/create-widget :pos [rx 0] :size [rw panel-h])]
    ;; --- showWindow (TOP: 158.5×136) ---
    (let [sw (cgui-core/create-widget :pos [0 0] :size [rw show-window-h])]
      ;; preview-area: 134×134 in showWindow (136h), center-aligned with y=-2 offset
      (let [pa (cgui-core/create-widget
                :pos [(/ (- rw 134.0) 2) -1.0] :size [134.0 134.0])]
        (cgui-core/set-name! pa "preview-area")
        (cgui-core/add-widget! sw pa))
      ;; Preview nav buttons
      (let [refresh! #(refresh-preview! root pvs
                                    :preview-item preview-item
                                    :preview-type preview-type)]
        (let [bl (cgui-core/create-widget :pos [5 41.75] :size [12 52])]
          (comp/add-component! bl (comp/draw-texture (gui-tex "button/button_left_2.png")))
          (events/on-left-click bl (fn [_] (preview/cycle-sub-view! pvs :prev) (refresh!)))
          (cgui-core/set-name! bl "btn-left")
          (cgui-core/set-visible! bl false)
          (cgui-core/add-widget! sw bl))
        (let [br (cgui-core/create-widget :pos [140 41.75] :size [12 52])]
          (comp/add-component! br (comp/draw-texture (gui-tex "button/button_right_2.png")))
          (events/on-left-click br (fn [_] (preview/cycle-sub-view! pvs :next) (refresh!)))
          (cgui-core/set-name! br "btn-right")
          (cgui-core/set-visible! br false)
          (cgui-core/add-widget! sw br)))
      ;; tag_area: ViewGroup tag buttons (populated by refresh-preview!)
      (let [ta (cgui-core/create-widget :pos [12 120.75] :size [133 18])]
        (cgui-core/set-name! ta "tag-area")
        (cgui-core/add-widget! sw ta))
      (cgui-core/add-widget! rp sw))
    ;; --- rightWindow (BOTTOM: 158.5×82) ---
    (let [rw (cgui-core/create-widget :pos [0 show-window-h] :size [rw right-window-h])]
      (comp/add-component! rw (comp/draw-texture (gui-tex "window_tutorial_left.png")))
      (let [tw (cgui-core/create-widget :pos [3 3] :size [riw 14])]
        (comp/add-component! tw (comp/text-box :text "" :font-size 10.0 :color 0xFFFFFFFF))
        (cgui-core/set-name! tw "title-text")
        (cgui-core/add-widget! rw tw))
      (let [bw (cgui-core/create-widget :pos [3 15] :size [riw 64])]
        (comp/add-component! bw (comp/text-box :text "Select a tutorial"
                                               :font-size 8.0 :color 0xFFFFFFFF))
        (cgui-core/set-name! bw "brief-text")
        (cgui-core/add-widget! rw bw))
      (cgui-core/add-widget! rp rw))
    (cgui-core/set-name! rp "right-panel")
    (when first-open? (cgui-core/set-visible! rp false))
    (cgui-core/add-widget! root rp)))

(defn- build-logos! [root]
  "Create 4 logo widgets with initial alpha=0 (faded in during animation)."
  (let [logo-cx (/ gw 2)
        l0h 137.0 l1h 59.0 l3h 37.25]
    (let [l0 (cgui-core/create-widget
              :pos [(- logo-cx 112.375) (- (/ gh 2) 32.5)]
              :size [224.75 l0h])]
      (comp/add-component! l0 (comp/draw-texture (gui-tex "tutorial/logo0.png") 0x00FFFFFF))
      (cgui-core/set-name! l0 "logo0")
      (cgui-core/add-widget! root l0))
    (let [l1 (cgui-core/create-widget
              :pos [(- logo-cx 112.375) (- (/ gh 2) 59.0)]
              :size [224.75 l1h])]
      (comp/add-component! l1 (comp/draw-texture (gui-tex "tutorial/logo1.png") 0x00FFFFFF))
      (cgui-core/set-name! l1 "logo1")
      (cgui-core/add-widget! root l1))
    (let [l2 (cgui-core/create-widget
              :pos [(- logo-cx 112.375) (- (/ gh 2) 59.0)]
              :size [224.75 l1h])]
      (comp/add-component! l2 (comp/draw-texture (gui-tex "tutorial/logo2.png") 0x00FFFFFF))
      (cgui-core/set-name! l2 "logo2")
      (cgui-core/add-widget! root l2))
    (let [l3 (cgui-core/create-widget
              :pos [(- logo-cx 18.625) (- (/ gh 2) 36.0)]
              :size [l3h l3h])]
      (comp/add-component! l3 (comp/draw-texture (gui-tex "tutorial/logo3.png") 0x00FFFFFF))
      (cgui-core/set-name! l3 "logo3")
      (cgui-core/add-widget! root l3))))

(defn- build-glow-lines! [root]
  "Create static glow line widgets positioned at logo1 center."
  (let [glow-h 5.0
        logo1-center-y (+ (- (/ gh 2) 59.0) (/ 59.0 2) 15)
        glow-y (- logo1-center-y (/ glow-h 2))]
    (let [gr (cgui-core/create-widget
              :pos [(- (/ gw 2) 112.375) glow-y] :size [0 glow-h])]
      (comp/add-component! gr (comp/tint 0x88FFFFFF))
      (cgui-core/set-name! gr "glow-right")
      (cgui-core/add-widget! root gr))
    (let [gl (cgui-core/create-widget
              :pos [(- (/ gw 2) 112.375) glow-y] :size [0 glow-h])]
      (comp/add-component! gl (comp/tint 0x88FFFFFF))
      (cgui-core/set-name! gl "glow-left")
      (cgui-core/add-widget! root gl))))

(defn- setup-static-glow! [root]
  "Non-first-open glow on logo1 matching upstream GuiTutorial non-first-open
  FrameEvent handler.  Hides logos 0,2,3; shows glow lines at the original
  positions (ln-ln2 → ln on right, -ln → -(ln-ln2) on left, width = ln2).
  Registers a per-frame handler that modulates glow alpha with a slow sine
  wave for the subtle breathing effect matching ACRenderingHelper.drawGlow."
  (doseq [nm ["logo0" "logo2" "logo3"]]
    (when-let [lw (cgui-core/find-widget root nm)]
      (cgui-core/set-visible! lw false)))
  (let [ln     500.0   ;; outer edge of glow (matches original `ln`)
        ln2    300.0   ;; glow width (matches original `ln2`)
        glow-h 5.0     ;; glow line height (matches original `ht`)
        logo1-x (- (/ gw 2) 112.375)
        logo1-w 224.75
        logo1-center-x (+ logo1-x (/ logo1-w 2))
        glow-y (+ (- (/ gh 2) 59.0) (/ 59.0 2) 15 (- (/ glow-h 2)))
        ;; Glow positions matching original non-first-open:
        ;;   lineglow(ln - ln2, ln, ht)  → right: x0=200, x1=500 relative to center
        ;;   lineglow(-ln, -(ln - ln2), ht) → left:  x0=-500, x1=-200 relative to center
        right-start (+ logo1-center-x (- ln ln2))    ;; 200px right of center
        left-start  (- logo1-center-x ln)            ;; 500px left of center
        glow-width  ln2                              ;; 300px wide
        ;; Breathing animation state
        start-ms (System/currentTimeMillis)
        period-ms 2800.0  ;; ~2.8s per breath cycle
        base-alpha 0x44   ;; ~27% opacity at rest
        amp-alpha  0x33]  ;; amplitude → oscillates between ~7% and ~47%
    ;; Position glow widgets
    (when-let [gr (cgui-core/find-widget root "glow-right")]
      (cgui-core/set-pos! gr right-start glow-y)
      (cgui-core/set-size! gr glow-width glow-h))
    (when-let [gl (cgui-core/find-widget root "glow-left")]
      (cgui-core/set-pos! gl left-start glow-y)
      (cgui-core/set-size! gl glow-width glow-h))
    (apply-logo-alpha! root "logo1" 255)
    ;; Per-frame alpha breathing — matches original ACRenderingHelper.drawGlow
    ;; subtle animated glow effect on logo1 return visits
    (let [done? (atom false)]
      (events/on-frame root
        (fn [_]
          (when-not @done?
            (let [elapsed (- (System/currentTimeMillis) start-ms)
                  phase (/ (mod elapsed period-ms) period-ms)
                  sin-val (Math/sin (* 2.0 Math/PI phase))
                  ;; Map sin [-1,1] to alpha range [base-amp, base+amp]
                  alpha (bit-and 0xFF (unchecked-int (+ base-alpha (* amp-alpha sin-val))))
                  argb (bit-or (bit-shift-left alpha 24) 0x00FFFFFF)]
              (when-let [gr (cgui-core/find-widget root "glow-right")]
                (when-let [tint-comp (comp/get-tint-component gr)]
                  (comp/set-tint! tint-comp argb)))
              (when-let [gl (cgui-core/find-widget root "glow-left")]
                (when-let [tint-comp (comp/get-tint-component gl)]
                  (comp/set-tint! tint-comp argb))))))))))

;; --- Main open! function ---

(defn open! [player]
  "Open the tutorial GUI. Syncs client state, builds the three-panel widget tree,
  sets up first-open animation (or static glow for return visits), and opens the screen."
  (log/info "Opening tutorial GUI")
  (let [player-uuid (some-> player .getUUID str)
        _ (client-state/ensure-client-state! player-uuid)
        _ (client-state/request-sync! {:player-uuid player-uuid})
        lang (tut-content/current-lang)
        root (cgui-core/create-widget :size [gw gh])
        entries (tut-registry/all-tutorials)
        first-open? (client-state/first-open? player-uuid)
        content-ctr (cgui-core/create-widget
                     :pos [(+ cx cox) coy] :size [cow 5000.0])
        ;; Mutable UI state — atoms shared across builder functions and handlers
        ui {:current-tut-id   (atom nil)
            :scroll-y         (atom 0.0)
            :max-scroll       (atom 0.0)
            :scroll-progress  (atom 0.0)
            :pvs              (atom (preview/create-preview-state :welcome))
            :preview-item     (atom nil)
            :preview-type     (atom :icon)
            :anim-start       (atom nil)}]
    ;; Build UI sections
    (build-background! root)
    (build-left-panel! root entries lang first-open? content-ctr
                       ui player-uuid)
    (build-center-panel! root content-ctr first-open? ui)
    (build-right-panel! root (:pvs ui) (:preview-item ui) (:preview-type ui)
                       first-open?)
    (build-logos! root)
    (build-glow-lines! root)
    ;; Setup animation (first-open or static)
    (if first-open?
      (do (reset! (:anim-start ui) (System/currentTimeMillis))
          (setup-first-open-animation! root (:anim-start ui)))
      (setup-static-glow! root))
    ;; --- Hover detection for ViewGroup tag buttons ---
    ;; Bridge's mouseMoved stores mouse position in root metadata
    ;; (relative to root origin). Frame handler checks if mouse is
    ;; over a tag button and updates brief text (matching upstream
    ;; GuiTutorial tag_area hover behavior).
    (let [hover-last-tag-idx (atom -1)
          ;; Tag layout constants — tags are N×18 inside tag-area
          ;; right-panel.x + showWindow.x + tag-area.x = 265.5 + 0 + 12 = 277.5
          ;; right-panel.y + showWindow.y + tag-area.y = 0 + 0 + 120.75 = 120.75
          tag-base-x (+ rx 12.0)
          tag-base-y 120.75
          tag-size 18.0
          tag-step 17.0]
      (events/on-frame root
        (fn [_]
          (let [mx (get @(:metadata root) :last-mouse-x -1)
                my (get @(:metadata root) :last-mouse-y -1)
                pvs (:pvs ui)
                {:keys [view-groups]} @pvs
                tag-count (count (or view-groups []))
                ;; Find which tag (if any) the mouse is over
                hover-idx (when (and (>= my tag-base-y)
                                     (< my (+ tag-base-y tag-size)))
                            (loop [i 0]
                              (when (< i tag-count)
                                (let [tx (+ tag-base-x (* i tag-step))]
                                  (if (and (>= mx tx) (< mx (+ tx tag-size)))
                                    i
                                    (recur (inc i)))))))]
            ;; Only update brief text on hover change (avoid flicker)
            (when (not= @hover-last-tag-idx hover-idx)
              (reset! hover-last-tag-idx hover-idx)
              (when-let [bw (cgui-core/find-widget root "brief-text")]
                (when-let [tb (comp/get-textbox-component bw)]
                  (comp/set-text! tb
                    (if (and hover-idx (>= hover-idx 0) (< hover-idx tag-count))
                      (or (:display-text (nth view-groups hover-idx)) "")
                      (preview/display-text pvs))))))))))
    ;; Open the screen — bridge handles 3D preview rendering via preview atoms
    (client-bridge/open-simple-gui! root "MisakaCloud Terminal"
      {:preview-item-atom (:preview-item ui)
       :preview-type-atom  (:preview-type ui)})))
