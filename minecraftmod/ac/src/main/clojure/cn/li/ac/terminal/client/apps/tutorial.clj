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
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]))

;; --- Layout constants (matching original tutorial.xml) ---

(def gw 427.0) (def gh 240.0)
(def lw 85.0)  (def lx 7.0)   (def lix 6.6) (def liy 7.0)
(def liw 72.0) (def eh 12.0)   ;; entry height 12px matches original
(def cx (+ lx lw)) (def cw 172.0)
(def cox 2.0)  (def cow (- cw (* 2 cox)))  (def coy 4.0)
(def coh (- gh (* 2 coy)))
(def rx (+ cx cw)) (def rw 158.5)
(def rix 6.0)  (def riw (- rw (* 2 rix)))

;; Scroll bar dimensions
(def scroll-track-w 9.0)
(def scroll-thumb-h 53.0)

;; Dimming color — matches original Colors.fromFloatMono(0.6f) = 0.6,0.6,0.6
(def unlearned-color 0xFF999999)
(def learned-color 0xFFFFFFFF)

;; --- Texture helper ---

(defn- gui-tex [name]
  (modid/asset-path "textures/guis" name))

;; --- First-open flag ---

(defonce ^:private first-open-done? (atom false))

;; --- Content helpers ---

(defn- clear-content! [content-ctr]
  (dotimes [n 200]
    (when-let [w (cgui-core/find-widget content-ctr (str "ct-" n))]
      (cgui-core/remove-widget! content-ctr w))))

(defn- render-content! [content-ctr content-str]
  (clear-content! content-ctr)
  (let [segs (mr/render-segments content-str nil)]
    (loop [sg segs y 0.0 n 0]
      (if (seq sg)
        (let [{:keys [text font-size color bold?]} (first sg)
              w (cgui-core/create-widget :pos [0.0 y] :size [cow mr/line-height])]
          (comp/add-component! w (comp/text-box :text text :font-size font-size
                                                :color color :font (when bold? :ac-bold)))
          (cgui-core/set-name! w (str "ct-" n))
          (cgui-core/add-widget! content-ctr w)
          (recur (rest sg) (+ y mr/line-height) (inc n)))
        y))))

(defn- reposition-content! [content-ctr scroll-y]
  (cgui-core/set-pos! content-ctr [(+ cx cox) (- coy @scroll-y)]))

(defn- clamp-scroll! [scroll-y max-scroll]
  (let [clamped (max 0.0 (min (double @scroll-y) (double max-scroll)))]
    (when (not= @scroll-y clamped)
      (reset! scroll-y clamped))))

;; --- Preview refresh ---

(defn- refresh-preview! [root pvs]
  (when-let [area (cgui-core/find-widget root "preview-area")]
    (when-let [old (cgui-core/find-widget area "current-preview")]
      (cgui-core/remove-widget! area old))
    (when-let [spec (preview/current-preview-spec @pvs)]
      (when-let [pw (preview/build-preview-widget spec)]
        (cgui-core/set-name! pw "current-preview")
        (cgui-core/add-widget! area pw))))
  (when-let [bw (cgui-core/find-widget root "brief-text")]
    (when-let [tb (comp/get-textbox-component bw)]
      (comp/set-text! tb (preview/display-text @pvs))))
  (when-let [ti (cgui-core/find-widget root "tag-icon")]
    (when-let [od (comp/get-drawtexture-component ti)]
      (comp/remove-component! ti od))
    (comp/add-component! ti (comp/draw-texture
                             (get preview/tag-textures
                                  (preview/current-tag @pvs)
                                  (preview/tag-textures :view)))))
  (let [cnt (count (:specs @pvs))]
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

(defn- setup-first-open-animation!
  "Register a frame handler that fades in logos with staggered timing
  and reveals panels after animation completes.  Logos: 4 widgets with
  staggered start times matching original AC GuiTutorial."
  [root anim-start]
  (let [logo-timings [["logo3" 100 300]   ;; start 0.10s, duration 0.30s
                      ["logo2" 650 300]    ;; start 0.65s, duration 0.30s
                      ["logo1" 1300 300]   ;; start 1.30s, duration 0.30s
                      ["logo0" 1750 300]]] ;; start 1.75s, duration 0.30s
    (events/on-frame root
      (fn [_]
        (when-not @anim-start
          (reset! anim-start (System/currentTimeMillis)))
        (let [elapsed (- (System/currentTimeMillis) @anim-start)]
          ;; Staggered logo fade-in
          (doseq [[logo-name start-ms dur-ms] logo-timings]
            (apply-logo-alpha! root logo-name (logo-fade-alpha elapsed start-ms dur-ms)))
          ;; After animation completes (~2.0s), reveal panels
          (when (>= elapsed 2000)
            (reset! first-open-done? true)
            (doseq [nm ["left-panel" "center-panel" "right-panel"]]
              (when-let [pw (cgui-core/find-widget root nm)]
                (cgui-core/set-visible! pw true)))
            (doseq [nm ["logo0" "logo1" "logo2" "logo3"]]
              (when-let [lw (cgui-core/find-widget root nm)]
                (cgui-core/set-visible! lw false)))))))))

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
        first-open? (not @first-open-done?)
        anim-start (atom nil)]

    ;; Background
    (let [bg (cgui-core/create-widget :pos [0 0] :size [gw gh])]
      (comp/add-component! bg (comp/draw-texture (gui-tex "data_terminal/app_back.png")))
      (cgui-core/add-widget! root bg))

    ;; Left panel — tutorial entries
    (let [lp (cgui-core/create-widget :pos [lx 0] :size [lw gh])]
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
                (reset! current-tut-id (:id tut))
                (let [cd (tut-content/load-tutorial-content (:id tut))
                      total-h (render-content! content-ctr (:content cd))]
                  (reset! max-scroll (max 0.0 (- total-h coh)))
                  (reset! scroll-y 0.0)
                  (reset! scroll-progress 0.0)
                  (reposition-content! content-ctr scroll-y)
                  (reset! pvs (preview/create-preview-state (:id tut)))
                  (refresh-preview! root pvs)
                  (when-let [bw (cgui-core/find-widget root "brief-text")]
                    (when-let [tb (comp/get-textbox-component bw)]
                      (comp/set-text! tb (or (:brief cd) ""))))))))
          (cgui-core/add-widget! lp ew)))
      (cgui-core/set-name! lp "left-panel")
      (when first-open? (cgui-core/set-visible! lp false))
      (cgui-core/add-widget! root lp))

    ;; Center panel — scrollable content
    (let [cp (cgui-core/create-widget :pos [cx 0] :size [(+ cw scroll-track-w) gh])]
      (cgui-core/add-widget! cp content-ctr)
      (let [track (cgui-core/create-widget
                   :pos [(- cw 2.0) 2.0] :size [scroll-track-w (- gh 4.0)])]
        (comp/add-component! track (comp/tint 0x22FFFFFF))
        (cgui-core/set-name! track "scroll-track")
        (cgui-core/add-widget! cp track))
      (let [thumb (cgui-core/create-widget
                   :pos [(- cw 2.0) 2.0] :size [scroll-track-w scroll-thumb-h])]
        (comp/add-component! thumb (comp/tint 0x88FFFFFF))
        (comp/add-component! thumb (comp/draggable))
        (cgui-core/set-name! thumb "scroll-thumb")
        (events/on-drag thumb
          (fn [evt]
            (when (pos? @max-scroll)
              (let [track-h (- gh 4.0)
                    thumb-travel (- track-h scroll-thumb-h)
                    scroll-delta (* (/ (double (.dy evt)) thumb-travel) @max-scroll)]
                (swap! scroll-y + scroll-delta)
                (clamp-scroll! scroll-y @max-scroll)
                (let [progress (/ @scroll-y @max-scroll)
                      thumb-y (+ 2.0 (* progress thumb-travel))]
                  (cgui-core/set-pos! thumb [(- cw 2.0) thumb-y])
                  (reset! scroll-progress progress)
                  (reposition-content! content-ctr scroll-y))))))
        (cgui-core/add-widget! cp thumb))
      (cgui-core/set-name! cp "center-panel")
      (when first-open? (cgui-core/set-visible! cp false))
      (cgui-core/add-widget! root cp))

    ;; Right panel — preview + brief + tag
    (let [rp (cgui-core/create-widget :pos [rx 0] :size [rw gh])]
      (comp/add-component! rp (comp/draw-texture (gui-tex "window_tutorial_right.png")))
      (let [pa (cgui-core/create-widget :pos [8 8] :size [(- rw 16) (- gh 98)])]
        (cgui-core/set-name! pa "preview-area")
        (cgui-core/add-widget! rp pa))
      (let [refresh! #(refresh-preview! root pvs)]
        (let [bl (cgui-core/create-widget :pos [8 50] :size [16 60])]
          (comp/add-component! bl (comp/text-box :text "<" :font-size 12.0 :color 0xFFFFFFFF))
          (events/on-left-click bl (fn [_] (preview/cycle-preview! pvs :prev) (refresh!)))
          (cgui-core/set-name! bl "btn-left")
          (cgui-core/set-visible! bl false)
          (cgui-core/add-widget! rp bl))
        (let [br (cgui-core/create-widget :pos [(- rw 24) 50] :size [16 60])]
          (comp/add-component! br (comp/text-box :text ">" :font-size 12.0 :color 0xFFFFFFFF))
          (events/on-left-click br (fn [_] (preview/cycle-preview! pvs :next) (refresh!)))
          (cgui-core/set-name! br "btn-right")
          (cgui-core/set-visible! br false)
          (cgui-core/add-widget! rp br)))
      (let [ti (cgui-core/create-widget :pos [12 (- gh 94)] :size [18 18])]
        (comp/add-component! ti (comp/draw-texture (preview/tag-textures :view)))
        (cgui-core/set-name! ti "tag-icon")
        (cgui-core/add-widget! rp ti))
      (let [bw (cgui-core/create-widget :pos [rix (- gh 82)] :size [riw 69])]
        (comp/add-component! bw (comp/text-box :text "Select a tutorial"
                                               :font-size 8.0 :color 0xFFFFFFFF))
        (cgui-core/set-name! bw "brief-text")
        (cgui-core/add-widget! rp bw))
      (cgui-core/set-name! rp "right-panel")
      (when first-open? (cgui-core/set-visible! rp false))
      (cgui-core/add-widget! root rp))

    ;; Logo widgets (all 4 — matching original AC)
    (doseq [[nm tex size pos] [["logo0" "logo0.png" [224 137] [(- (/ gw 2) 112) 20]]
                               ["logo1" "logo1.png" [224 59]  [(- (/ gw 2) 112) 157]]
                               ["logo2" "logo2.png" [224 137] [(- (/ gw 2) 112) 20]]
                               ["logo3" "logo3.png" [224 137] [(- (/ gw 2) 112) 20]]]]
      (let [lw (cgui-core/create-widget :pos pos :size size)]
        (comp/add-component! lw (comp/draw-texture (gui-tex (str "tutorial/" tex)) [255 255 255 0]))
        (cgui-core/set-name! lw nm)
        (cgui-core/add-widget! root lw)))

    ;; First-open animation or direct open
    (if first-open?
      (setup-first-open-animation! root anim-start)
      (doseq [nm ["logo0" "logo1" "logo2" "logo3"]]
        (when-let [lw (cgui-core/find-widget root nm)]
          (cgui-core/set-visible! lw false))))

    ;; 3D rotating item preview
    (let [preview-item (atom nil)]
      (add-watch pvs :preview-3d
        (fn [_ _ _ st]
          (when-let [spec (preview/current-preview-spec @st)]
            (reset! preview-item (or (:item-id spec) (:texture spec))))))
      (client-bridge/open-simple-gui! root "MisakaCloud Terminal"
        {:preview-item-atom preview-item}))))
