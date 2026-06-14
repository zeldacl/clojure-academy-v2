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
(def cox 2.0)  (def cow (- cw (* 2 cox)))  (def coy 4.0)
(def coh (- panel-h (* 2 coy)))  ;; content visible height
(def rx (+ cx cw)) (def rw 158.5)
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

;; --- Content helpers ---

(defn- clear-content! [content-ctr]
  (dotimes [n 200]
    (when-let [w (cgui-core/find-widget content-ctr (str "ct-" n))]
      (cgui-core/remove-widget! content-ctr w))))

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
  (cgui-core/set-pos! content-ctr [(+ cx cox) (- coy @scroll-y)]))

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
      (let [data (if (= (:type view) :recipe)
                   {:recipe-kind (name (:recipe-kind view))
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
  (when-let [lw (cgui-core/find-widget root logo-name)]
    (when-let [dt (comp/get-drawtexture-component lw)]
      (swap! (:state dt) assoc :color
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
            (when-let [l3 (cgui-core/find-widget root "logo3")]
              (let [y-t (max 0.0 (min 1.0 (/ (- elapsed 700.0) 400.0)))
                    l3y (+ 63.0 (* y-t (- -36.0 63.0)))]
                ;; Update y relative to center
                (cgui-core/set-pos! l3 [(- (/ gw 2) 18.625) (- (/ gh 2) l3y)])))
            ;; Glow line animation: dt = elapsed - glow-frame-offset-ms
            (let [dt (- elapsed glow-frame-offset-ms)
                  b1 300.0 b2 200.0]
              (when (>= dt 0)
                (if (< dt b1)
                  ;; Phase 1: grow outward 0→500
                  (let [len (* ln (/ dt b1))]
                    (when (> len cl)
                      (when-let [gr (cgui-core/find-widget root "glow-right")]
                        (cgui-core/set-pos! gr [(- logo1-center-x cl) (second (cgui-core/get-pos gr))])
                        (cgui-core/set-size! gr [(- len cl) glow-h])
                        (cgui-core/set-visible! gr true))
                      (when-let [gl (cgui-core/find-widget root "glow-left")]
                        (cgui-core/set-pos! gl [(- logo1-center-x len) (second (cgui-core/get-pos gl))])
                        (cgui-core/set-size! gl [(- len cl) glow-h])
                        (cgui-core/set-visible! gl true))))
                  ;; Phase 2: contract
                  (let [ldt (min (- dt b1) b2)
                        len2 (+ (* (- ln cl cl) (- 1.0 (/ ldt b2))) cl)]
                    (when-let [gr (cgui-core/find-widget root "glow-right")]
                      (cgui-core/set-pos! gr [(- logo1-center-x (- ln len2)) (second (cgui-core/get-pos gr))])
                      (cgui-core/set-size! gr [(- ln (- ln len2)) glow-h]))
                    (when-let [gl (cgui-core/find-widget root "glow-left")]
                      (cgui-core/set-pos! gl [(- logo1-center-x ln) (second (cgui-core/get-pos gl))])
                      (cgui-core/set-size! gl [(- ln (- ln len2)) glow-h])))))
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

;; --- Main open! function ---

(defn open! [player]
  (log/info "Opening tutorial GUI")
  (let [root (cgui-core/create-widget :size [gw gh])
        entries (tut-registry/all-tutorials)
        activated (into #{} (keep #(when (:default-installed? %) (:id %)) entries))
        pvs (atom (preview/create-preview-state :welcome))
        scroll-y (atom 0.0)
        max-scroll (atom 0.0)
        scroll-progress (atom 0.0)
        content-ctr (cgui-core/create-widget :pos [(+ cx cox) coy] :size [cow 5000.0])
        current-tut-id (atom nil)
        player-uuid (some-> player .getUUID str)
        first-open? (client-state/first-open? player-uuid)
        anim-start (atom nil)
        ;; 3D preview atoms (fed to cgui_screen_bridge)
        preview-item (atom nil)
        preview-type (atom :icon)]

    ;; Background
    (let [bg (cgui-core/create-widget :pos [0 0] :size [gw gh])]
      (comp/add-component! bg (comp/draw-texture (gui-tex "data_terminal/app_back.png")))
      (cgui-core/add-widget! root bg))

    ;; Left panel — tutorial entries (85×220.5)
    (let [lp (cgui-core/create-widget :pos [lx 0] :size [lw panel-h])]
      (comp/add-component! lp (comp/draw-texture (gui-tex "window_tutorial_left.png")))
      (doseq [[idx tut] (map-indexed vector entries)]
        (let [y (+ liy (* idx eh))
              active? (contains? activated (:id tut))
              title (or (:title (tut-content/load-tutorial-content (:id tut)))
                        (name (:id tut)))
              ew (cgui-core/create-widget :pos [lix y] :size [liw eh])]
          (comp/add-component! ew
            (comp/text-box :text title :font-size 8.0
                           :color (if active? learned-color unlearned-color)
                           :align :left))
          (events/on-left-click ew
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
                  (let [cd (tut-content/load-tutorial-content (:id tut))]
                    ;; Center panel visibility follows tut.isActivated(player)
                    (when-let [cp (cgui-core/find-widget root "center-panel")]
                      (cgui-core/set-visible! cp active?))
                    (when active?
                      (let [misaka-id (client-state/get-misaka-id player-uuid)
                            total-h (render-content! content-ctr (:content cd) misaka-id)]
                        (reset! max-scroll (max 0.0 (- total-h coh)))
                        (reset! scroll-y 0.0)
                        (reset! scroll-progress 0.0)
                        (reposition-content! content-ctr scroll-y)))
                    (reset! pvs (preview/create-preview-state (:id tut)))
                    (refresh-preview! root pvs
                                     :preview-item preview-item
                                     :preview-type preview-type)
                    ;; Update title + brief text in rightWindow
                    (when-let [tw (cgui-core/find-widget root "title-text")]
                      (when-let [tb (comp/get-textbox-component tw)]
                        (comp/set-text! tb (or (:title cd) ""))))
                    (when-let [bw (cgui-core/find-widget root "brief-text")]
                      (when-let [tb (comp/get-textbox-component bw)]
                        (comp/set-text! tb (or (:brief cd) "")))))))))
          (cgui-core/add-widget! lp ew)))
      (cgui-core/set-name! lp "left-panel")
      (when first-open? (cgui-core/set-visible! lp false))
      (cgui-core/add-widget! root lp))

    ;; Center panel — scrollable content (172×220.5 + 9.5 scroll track)
    (let [cp (cgui-core/create-widget :pos [cx 0] :size [cw panel-h])]
      (cgui-core/add-widget! cp content-ctr)
      (cgui-core/set-name! cp "center-panel")
      (when first-open? (cgui-core/set-visible! cp false))
      (cgui-core/add-widget! root cp))
    ;; Scroll track + thumb (right of center panel)
    (let [track-x (+ cx cw)  ;; 92+172=264
          track (cgui-core/create-widget
                 :pos [track-x 2.0] :size [scroll-track-w scroll-track-h])]
      (comp/add-component! track (comp/draw-texture (gui-tex "button/widget_scroll_1.png")))
      (cgui-core/set-name! track "scroll-track")
      (cgui-core/add-widget! root track))
    (let [thumb-travel (- scroll-thumb-max-y scroll-thumb-min-y)
          thumb-x (+ cx cw)]
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
                  (cgui-core/set-pos! thumb [thumb-x thumb-y])
                  (reset! scroll-progress progress)
                  (reposition-content! content-ctr scroll-y))))))
        (events/on-mouse-scroll thumb
          (fn [evt]
            (when (pos? @max-scroll)
              (let [new-y (+ @scroll-y (* (:delta-y evt) 10.0))]
                (reset! scroll-y (max 0.0 (min @max-scroll new-y)))
                (let [progress (/ @scroll-y @max-scroll)
                      thumb-y (+ scroll-thumb-min-y (* progress thumb-travel))]
                  (cgui-core/set-pos! thumb [thumb-x thumb-y])
                  (reposition-content! content-ctr scroll-y))))))
        (cgui-core/add-widget! root thumb)))

    ;; Right panel — split into showWindow (TOP 158.5×136) + rightWindow (BOTTOM 158.5×82)
    (let [rp (cgui-core/create-widget :pos [rx 0] :size [rw panel-h])]
      ;; --- showWindow (TOP: 158.5×136) ---
      (let [sw (cgui-core/create-widget :pos [0 0] :size [rw show-window-h])]
        ;; preview-area: 134×134, centered in showWindow (tutorial.xml area widget)
        (let [pa (cgui-core/create-widget
                  :pos [(/ (- rw 134.0) 2) 0] :size [134.0 134.0])]
          (cgui-core/set-name! pa "preview-area")
          (cgui-core/add-widget! sw pa))
        ;; Preview nav buttons (texture-based, matching original)
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
        ;; tag_area: 133×18 at (12, 120.75) — ViewGroup tag buttons (Task 5 will populate)
        (let [ta (cgui-core/create-widget :pos [12 120.75] :size [133 18])]
          (cgui-core/set-name! ta "tag-area")
          (cgui-core/add-widget! sw ta))
        (cgui-core/add-widget! rp sw))
      ;; --- rightWindow (BOTTOM: 158.5×82) ---
      (let [rw (cgui-core/create-widget :pos [0 show-window-h] :size [rw right-window-h])]
        (comp/add-component! rw (comp/draw-texture (gui-tex "window_tutorial_left.png")))
        ;; title text at y=3
        (let [tw (cgui-core/create-widget :pos [3 3] :size [riw 14])]
          (comp/add-component! tw (comp/text-box :text "" :font-size 10.0 :color 0xFFFFFFFF))
          (cgui-core/set-name! tw "title-text")
          (cgui-core/add-widget! rw tw))
        ;; brief text at y=15
        (let [bw (cgui-core/create-widget :pos [3 15] :size [riw 64])]
          (comp/add-component! bw (comp/text-box :text "Select a tutorial"
                                                 :font-size 8.0 :color 0xFFFFFFFF))
          (cgui-core/set-name! bw "brief-text")
          (cgui-core/add-widget! rw bw))
        (cgui-core/add-widget! rp rw))
      (cgui-core/set-name! rp "right-panel")
      (when first-open? (cgui-core/set-visible! rp false))
      (cgui-core/add-widget! root rp))

    ;; Logo widgets — natural sizes scaled 0.25 per tutorial.xml
    ;; logo0: 899×548 ×0.25 = 224.75×137.0, center y=-32.5
    ;; logo1: 899×236 ×0.25 = 224.75×59.0,  center y=59
    ;; logo2: 899×236 ×0.25 = 224.75×59.0,  center y=59
    ;; logo3: 149×149 ×0.25 = 37.25×37.25, center y=-36
    (let [logo-cx (/ gw 2)
          l0h 137.0 l1h 59.0 l3h 37.25]
      (let [l0 (cgui-core/create-widget
                :pos [(- logo-cx 112.375) (- (/ gh 2) l0h 32.5)]
                :size [224.75 l0h])]
        (comp/add-component! l0 (comp/draw-texture (gui-tex "tutorial/logo0.png") [255 255 255 0]))
        (cgui-core/set-name! l0 "logo0")
        (cgui-core/add-widget! root l0))
      (let [l1 (cgui-core/create-widget
                :pos [(- logo-cx 112.375) (- (/ gh 2) 59.0)]
                :size [224.75 l1h])]
        (comp/add-component! l1 (comp/draw-texture (gui-tex "tutorial/logo1.png") [255 255 255 0]))
        (cgui-core/set-name! l1 "logo1")
        (cgui-core/add-widget! root l1))
      (let [l2 (cgui-core/create-widget
                :pos [(- logo-cx 112.375) (- (/ gh 2) 59.0)]
                :size [224.75 l1h])]
        (comp/add-component! l2 (comp/draw-texture (gui-tex "tutorial/logo2.png") [255 255 255 0]))
        (cgui-core/set-name! l2 "logo2")
        (cgui-core/add-widget! root l2))
      (let [l3 (cgui-core/create-widget
                :pos [(- logo-cx 18.625) (- (/ gh 2) 36.0)]
                :size [l3h l3h])]
        (comp/add-component! l3 (comp/draw-texture (gui-tex "tutorial/logo3.png") [255 255 255 0]))
        (cgui-core/set-name! l3 "logo3")
        (cgui-core/add-widget! root l3)))

    ;; Glow line widgets — animated rectangles either side of logo1 center
    ;; non-first-open: static, first-open: two-phase animation
    (let [glow-h 5.0
          logo1-center-y (+ (- (/ gh 2) 59.0) (/ 59.0 2) 15)  ;; logo1 center + 15 offset
          glow-y (- logo1-center-y (/ glow-h 2))]
      ;; glow-right: starts at logo1 center, grows right
      (let [gr (cgui-core/create-widget
                :pos [(- (/ gw 2) 112.375) glow-y] :size [0 glow-h])]
        (comp/add-component! gr (comp/tint 0x88FFFFFF))
        (cgui-core/set-name! gr "glow-right")
        (cgui-core/add-widget! root gr))
      ;; glow-left: starts at logo1 left edge, grows left
      (let [gl (cgui-core/create-widget
                :pos [(- (/ gw 2) 112.375) glow-y] :size [0 glow-h])]
        (comp/add-component! gl (comp/tint 0x88FFFFFF))
        (cgui-core/set-name! gl "glow-left")
        (cgui-core/add-widget! root gl)))

    ;; First-open animation or direct open
    (if first-open?
      (setup-first-open-animation! root anim-start)
      ;; Non-first-open: show logo1 + static glow, hide other logos
      ;; center/right panels hidden until first entry click
      (do
        (doseq [nm ["logo0" "logo2" "logo3"]]
          (when-let [lw (cgui-core/find-widget root nm)]
            (cgui-core/set-visible! lw false)))
        ;; Static glow lines on logo1: lineglow(200,500,5) / lineglow(-500,-200,5)
        (let [ln 500.0 ln2 300.0 cl 50.0 glow-h 5.0
              logo1-x (- (/ gw 2) 112.375)
              logo1-w 224.75
              logo1-center-x (+ logo1-x (/ logo1-w 2))]
          (when-let [gr (cgui-core/find-widget root "glow-right")]
            (cgui-core/set-pos! gr [(- logo1-center-x cl) (second (cgui-core/get-pos gr))])
            (cgui-core/set-size! gr [(- ln cl) glow-h]))
          (when-let [gl (cgui-core/find-widget root "glow-left")]
            (cgui-core/set-pos! gl [(- logo1-center-x ln) (second (cgui-core/get-pos gl))])
            (cgui-core/set-size! gl [(- ln cl) glow-h])))))

    ;; 3D rotating item preview — atoms already created in main let block
    (client-bridge/open-simple-gui! root "MisakaCloud Terminal"
      {:preview-item-atom preview-item
       :preview-type-atom  preview-type}))))
