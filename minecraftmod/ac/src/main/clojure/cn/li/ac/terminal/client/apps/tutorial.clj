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
  scroll-y, controlled by drag on the scroll-thumb widget.

  Widget tree loaded from tutorial.xml (matching original GuiTutorial).
  Logos stay in rightPart (original hierarchy) for correct alignment-based positioning."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.tutorial.content :as tut-content]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.ac.tutorial.markdown-renderer :as mr]
            [cn.li.ac.tutorial.client.preview :as preview]
            [cn.li.ac.tutorial.client.state :as client-state]
            [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

;; --- Layout constants (matching original tutorial.xml) ---

(def gw 427.0) (def gh 240.0)  ;; frame: 427×240
(def panel-h 220.5)              ;; leftPart/rightPart/centerPart height from tutorial.xml
(def lw 85.0)  (def lx 7.0)   (def lix 6.6) (def liy 7.0) (def lih 207.0)
(def liw 72.0) (def eh 12.0)   ;; entry height 12px matches original; list height 207 from XML
(def cx (+ lx lw)) (def cw 172.0)
;; cox = 5.0: text widget x=2 + 3px internal pad (matches upstream glTranslated(3,3-delta,0))
(def cox 5.0)  (def cow 160.0)  ;; upstream text widget: 160×210.5
(def coy 3.0)  (def coh 210.5)  ;; coy=3 matches upstream glTranslated(3, 3-delta, 0); coh=210.5 text height
;; rightPart from XML: x=92(=cx), w=332, h=220.5, alignHeight=CENTER in frame
(def rpw 332.0)
;; rightPart abs-y in frame = (frame.h - rightPart.h)/2 = (240-220.5)/2 = 9.75
(def rp-abs-y (/ (- gh panel-h) 2.0))
;; Logo1 from XML: 899×236, scale=0.25 → effective 224.75×59, CENTER in rightPart
;; Alignment uses SCALED dims: offset-x = (rpw - 899*0.25)/2 = (332-224.75)/2 = 53.625
;; offset-y = (panel-h - 236*0.25)/2 = (220.5-59)/2 = 80.75
;; abs = parent.abs + offset + raw_pos (raw_pos is NOT scaled)
(def logo1-abs-x (+ cx (/ (- rpw (* 899.0 0.25)) 2.0)))          ;; = 92+53.625 = 145.625
(def logo1-abs-y (+ rp-abs-y (/ (- panel-h (* 236.0 0.25)) 2.0) 59.0))  ;; = 9.75+80.75+59 = 149.5
;; Glow center y = logo1-abs-y + (236/2+15)*0.25 = 149.5+33.25 = 182.75
(def glow-center-y (+ logo1-abs-y (* (+ (/ 236.0 2.0) 15.0) 0.25)))
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

;; --- Recursive widget search (matches original LambdaLib2 CGui.getWidget) ---

(defn- find-widget-recursive
  "Recursively search entire widget tree for a named widget.
  Matches original LambdaLib2 CGui.getWidget() behavior."
  [root name]
  (if (= (str name) (cgui-core/get-name root))
    root
    (some #(find-widget-recursive % name)
          (cgui-core/get-widgets root))))

;; --- Content cache ---

(def ^:private content-cache (atom {}))

(defn- cached-render-content! [content-ctr tut-id lang content-str misaka-id]
  (let [cache-key [tut-id lang (str misaka-id)]
        cached (get @content-cache cache-key)]
    (let [segs (if cached
                 (:segs cached)
                 (mr/render-segments content-str misaka-id))
          total-h (if cached
                    (:total-h cached)
                    (loop [sg segs y 0.0]
                      (if (seq sg)
                        (let [seg (first sg)
                              h (if (= (:type seg) :image)
                                  (or (:img-h seg) 100.0)
                                  mr/line-height)]
                          (recur (rest sg) (+ y h)))
                        y)))]
      (cgui-core/clear-widgets! content-ctr)
      (loop [sg segs y 0.0 n 0]
        (when (seq sg)
          (let [seg (first sg)
                image? (= (:type seg) :image)
                h (if image? (or (:img-h seg) 100.0) mr/line-height)
                ;; Images capped at markdown width-limit (150) so they don't
                ;; overlap the scroll bar. Text widgets use full cow width
                ;; (text wraps at max-content-width anyway, matching original).
                seg-w (if image? mr/max-content-width cow)
                w (cgui-core/create-widget :pos [0.0 y] :size [seg-w h])]
            (if image?
              (comp/add-component! w (comp/draw-texture (:texture-path seg) 0xFFFFFFFF))
              (let [{:keys [text font-size color bold?]} seg]
                (comp/add-component! w (comp/text-box :text text :font-size font-size
                                                      :color color :font (when bold? :ac-bold)))))
            (cgui-core/set-name! w (str "ct-" n))
            (cgui-core/add-widget! content-ctr w)
            (recur (rest sg) (+ y h) (inc n)))))
      (when-not cached
        (swap! content-cache assoc cache-key {:segs segs :total-h total-h}))
      total-h)))

;; --- Content helpers ---

(defn- reposition-content! [content-ctr scroll-y]
  (cgui-core/set-pos! content-ctr cox (- coy @scroll-y)))

(defn- clamp-scroll! [scroll-y max-scroll]
  (let [clamped (max 0.0 (min (double @scroll-y) (double max-scroll)))]
    (when (not= @scroll-y clamped)
      (reset! scroll-y clamped))))

;; --- Preview refresh ---

(defn- refresh-preview! [root pvs & {:keys [preview-item preview-type]}]
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
  (when-let [area (find-widget-recursive root "preview-area")]
    (when-let [old (cgui-core/find-widget area "current-preview")]
      (cgui-core/remove-widget! area old))
    (when-let [view (preview/current-sub-view @pvs)]
      (when-let [pw (preview/build-preview-widget view)]
        (cgui-core/set-name! pw "current-preview")
        (cgui-core/add-widget! area pw))))
  (when-let [bw (find-widget-recursive root "brief-text")]
    (when-let [tb (comp/get-textbox-component bw)]
      (comp/set-text! tb (preview/display-text pvs))))
  (when-let [ta (find-widget-recursive root "tag-area")]
    (doseq [nm ["tag-0" "tag-1" "tag-2" "tag-3" "tag-4"
                "tag-5" "tag-6" "tag-7" "tag-8" "tag-9"]]
      (when-let [w (cgui-core/find-widget ta nm)]
        (cgui-core/remove-widget! ta w)))
    ;; Remove old tooltip if present
    (when-let [old-tt (cgui-core/find-widget ta "tag-tooltip")]
      (cgui-core/remove-widget! ta old-tt))
    (let [{:keys [view-groups group-index]} @pvs
          [_ ta-h] (cgui-core/get-size ta)           ;; dynamic, matching original tagArea.transform.height
          sz (double ta-h)
          step (- sz 1.0)
          tag-widgets (atom [])]                      ;; tracked for hover tint transitions
      ;; Floating tooltip widget (matching original font.draw(0, -8) in tagArea FrameEvent)
      (let [tt (cgui-core/create-widget :pos [0 (- (+ sz 4.0))] :size [120 14])]
        (comp/add-component! tt (comp/text-box :text "" :font-size 10.0 :color 0xFFFFFFFF :align :left))
        (cgui-core/set-visible! tt false)
        (cgui-core/set-name! tt "tag-tooltip")
        (cgui-core/add-widget! ta tt)
        (swap! (:metadata root) assoc :tag-tooltip tt))
      (doseq [[idx vg] (map-indexed vector (or view-groups []))]
        (let [tag-w (cgui-core/create-widget :pos [(* idx step) 0] :size [sz sz])
              active? (= idx group-index)]
          (comp/add-component! tag-w
            (comp/draw-texture (preview/tag-textures (:tag vg) (preview/tag-textures :view))))
          ;; Idle tint 70% white (0xB3); hover→100% via frame handler.
          ;; Matching original Tint(Colors.monoBlend(1,.7f), Colors.monoBlend(1,1)).setAffectTexture()
          (comp/add-component! tag-w (comp/tint (if active? 0xFFFFFFFF 0xB3FFFFFF)))
          (events/on-left-click tag-w
            (fn [_]
              (preview/switch-view-group! pvs idx)
              (refresh-preview! root pvs)))
          (cgui-core/set-name! tag-w (str "tag-" idx))
          (cgui-core/add-widget! ta tag-w)
          (swap! tag-widgets conj tag-w)))
      (swap! (:metadata root) assoc :tag-widgets @tag-widgets)))
  (let [vg (preview/current-view-group @pvs)
        cnt (count (or (:sub-views vg) []))]
    (doseq [nm ["btn-left" "btn-right"]]
      (when-let [btn (find-widget-recursive root nm)]
        (cgui-core/set-visible! btn (> cnt 1))))))

;; --- First-open animation ---

(defn- logo-fade-alpha [elapsed-ms start-delay-ms duration-ms]
  ;; Linear clamp matching upstream MathUtils.clampd(0, 1, (delta-start)/tin)
  (let [t (max 0.0 (min 1.0 (/ (- elapsed-ms start-delay-ms) duration-ms)))]
    (int (* 255.0 t))))

(defn- apply-logo-alpha! [root logo-name alpha]
  (when-let [lw (find-widget-recursive root logo-name)]
    (when-let [dt (comp/get-drawtexture-component lw)]
      (swap! (:state dt) assoc :color
             (unchecked-int (bit-or (bit-shift-left alpha 24) 0x00FFFFFF))))))

(defn- setup-logo-fade-out!
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
                (when-let [lw (find-widget-recursive root nm)]
                  (cgui-core/set-visible! lw false)))
              (reset! done? true))))))))

(defn- setup-first-open-animation!
  [root anim-start]
  (let [;; Match upstream blend() calls:
        ;;   blend(logo2 0.65 0.3), blend(logo0 1.75 0.3),
        ;;   blend(leftPart 1.75 0.3), blend(logo1 1.3 0.3), blend(logo3 0.1 0.3)
        logo-timings [["logo3" 100 300]
                      ["logo2" 650 300]
                      ["logo1" 1300 300]
                      ["logo0" 1750 300]
                      ["left-panel" 1750 300]]
        ;; Glow drawn in logo1-local unscaled space (matches upstream exactly).
        ;; lineglow(cl, len, ht) / lineglow(-len, -cl, ht) in logo1's FrameEvent.
        ln 500.0 ln2 300.0 cl 50.0
        glow-h 12.0            ;; 3px visual / 0.25 logo1 scale
        glow-frame-offset-ms 400
        ;; logo1 center in logo1-local = 899/2 = 449.5 (matches glTranslated(width/2, ...))
        logo1-half-w (/ 899.0 2.0)
        ;; glow y in logo1-local = (glow-center-y - logo1-abs-y) / 0.25 ≈ 133.0
        glow-center-local (/ (- glow-center-y logo1-abs-y) 0.25)
        glow-y (- glow-center-local (/ glow-h 2.0))
        done? (atom false)]
    ;; Synchronous init: set alpha=0 and logo3 y=63 (matching upstream blend/blendy)
    (doseq [[logo-name _ _] logo-timings]
      (apply-logo-alpha! root logo-name 0))
    (when-let [l3 (find-widget-recursive root "logo3")]
      (cgui-core/set-pos! l3 0.0 63.0))
    (events/on-frame root
      (fn [_]
        (when (and (not @done?) @anim-start)
          (let [elapsed (- (System/currentTimeMillis) @anim-start)]
            (doseq [[logo-name start-ms dur-ms] logo-timings]
              (apply-logo-alpha! root logo-name (logo-fade-alpha elapsed start-ms dur-ms)))
            (when-let [l3 (find-widget-recursive root "logo3")]
              (let [y-t (max 0.0 (min 1.0 (/ (- elapsed 700.0) 400.0)))
                    l3y (+ 63.0 (* y-t (- -36.0 63.0)))]
                (cgui-core/set-pos! l3 0.0 l3y)))
            (let [dt (- elapsed glow-frame-offset-ms)
                  b1 300.0 b2 200.0]
              (when (>= dt 0)
                ;; Phase 1 (dt < b1): glow extends outward (upstream lineglow(cl, len, ht))
                ;; Phase 2 (dt >= b1): inner ends contract (upstream lineglow(ln-len2, len, ht))
                ;; All positions in logo1-local unscaled space.
                (if (< dt b1)
                  (let [len (* ln (/ dt b1))]
                    (when (> len cl)
                      (when-let [gr (find-widget-recursive root "glow-right")]
                        (cgui-core/set-pos! gr (+ logo1-half-w cl) glow-y)
                        (cgui-core/set-size! gr (- len cl) glow-h)
                        (cgui-core/set-visible! gr true))
                      (when-let [gl (find-widget-recursive root "glow-left")]
                        (cgui-core/set-pos! gl (- logo1-half-w len) glow-y)
                        (cgui-core/set-size! gl (- len cl) glow-h)
                        (cgui-core/set-visible! gl true))))
                  (let [ldt (min (- dt b1) b2)
                        len2 (+ (* (- ln cl cl) (- 1.0 (/ ldt b2))) cl)]
                    (when-let [gr (find-widget-recursive root "glow-right")]
                      (cgui-core/set-pos! gr (+ logo1-half-w (- ln len2)) glow-y)
                      (cgui-core/set-size! gr len2 glow-h))
                    (when-let [gl (find-widget-recursive root "glow-left")]
                      (cgui-core/set-pos! gl (- logo1-half-w ln) glow-y)
                      (cgui-core/set-size! gl len2 glow-h))))))
            ;; Upstream: listArea.transform.doesDraw = dt > 2.0
            (when (>= elapsed 2400)
              (when-let [list-w (find-widget-recursive root "list")]
                (cgui-core/set-visible! list-w true))
              (net-client/send-to-server (tut-msg/msg-id :tutorial/mark-first-open-done) {})
              (client-state/apply-sync! {:first-open? false})
              (reset! done? true))))))))

;; --- Brief markdown rendering ---

(defn- render-brief-markdown! [brief-widget brief-str misaka-id]
  ;; Keep title-text; only remove old brief segments (named "brief-*")
  (let [old-kids @(:children brief-widget)
        title-kid (first (filter #(= (cgui-core/get-name %) "title-text") old-kids))]
    (reset! (:children brief-widget) (if title-kid [title-kid] [])))
  ;; Upstream: glTranslated(3, 15, 0); renderer.widthLimit=130
  (let [brief-segs (mr/render-segments brief-str misaka-id 130.0)]
    (loop [sg brief-segs y 15.0 n 0]
      (when (seq sg)
        (let [seg (first sg)
              image? (= (:type seg) :image)
              h (if image? (or (:img-h seg) 100.0) mr/line-height)
              seg-w (if image? (or (:img-w seg) 130.0) 130.0)
              w (cgui-core/create-widget :pos [3.0 y] :size [seg-w h])]
          (if image?
            (comp/add-component! w (comp/draw-texture (:texture-path seg)))
            (comp/add-component! w
              (comp/text-box :text (:text seg) :font-size (:font-size seg)
                             :color (:color seg) :font (when (:bold? seg) :ac-bold))))
          (cgui-core/set-name! w (str "brief-" n))
          (cgui-core/add-widget! brief-widget w)
          (recur (rest sg) (+ y h) (inc n)))))))

;; --- Scroll helpers ---

(defn- reset-scroll! [scroll-y scroll-progress thumb-widget max-scroll content-ctr]
  (reset! scroll-y 0.0)
  (reset! scroll-progress 0.0)
  (when-let [thumb @thumb-widget]
    (cgui-core/set-pos! thumb 0.0 scroll-thumb-min-y))
  (when (pos? @max-scroll)
    (reset! max-scroll 0.0))
  (reposition-content! content-ctr scroll-y))

;; --- Widget builders ---

(defn- build-background! [root]
  (comp/add-component! root (comp/tint 0xC0101010)))

(declare make-entry-click-handler)

(defn- populate-left-panel!
  [root entries lang first-open? content-ctr ui player-uuid]
  (let [lp (find-widget-recursive root "left-panel")
        ;; Upstream: entries are children of listArea, not leftPart directly.
        ;; Hiding listArea hides entries too (listArea.doesDraw = dt > 2.0).
        list-w (cgui-core/find-widget lp "list")
        ;; Clear previous content to prevent event handler accumulation
        ;; (matching rebuildList() which removes old ElementList before adding new one)
        _ (cgui-core/clear-widgets! list-w)
        ;; Clip overflowing entries; scroll via mousewheel (matches upstream ElementList)
        _ (swap! (:metadata list-w) assoc :clip-children? true)
        ;; Scrollable content container for entry widgets
        list-ctr (cgui-core/create-widget :pos [0 0] :size [liw 5000.0])
        list-scroll-y (:list-scroll-y ui)
        ready? (client-state/ready?)
        is-active? (fn [tut]
                     (or (:default-installed? tut)
                         (and ready?
                           (client-state/is-activated? player-uuid (:id tut)))))
        {:keys [active inactive]} (reduce (fn [acc tut]
                                            (if (is-active? tut)
                                              (update acc :active conj tut)
                                              (update acc :inactive conj tut)))
                                          {:active [] :inactive []}
                                          entries)
        grouped (concat active inactive)
        total-h (* (count grouped) eh)
        max-scroll (max 0.0 (- total-h lih))
        ;; Hover state for entry tint (matching upstream Colors.whiteBlend(0.3f))
        entry-widgets (atom [])
        hovered-idx (atom -1)
        ;; Scale factor: :last-mouse-x/y are in screen pixels (post-scale),
        ;; but widget layout constants are in logical coords. Multiply by scale
        ;; to convert logical→screen-pixel for hover hit-testing.
        ui-scale (double (or @(:scale root) 1.0))
        ;; list-ctr root-local offsets: leftPanel.x + list.x, leftPanel.y + list.y
        ctr-root-x (* ui-scale (+ lx lix))
        ctr-root-y (* ui-scale (+ rp-abs-y liy))
        liw-px   (* ui-scale liw)
        eh-px    (* ui-scale eh)
        total-h-px (* ui-scale total-h)]
    (doseq [[idx tut] (map-indexed vector grouped)]
      (let [y (* idx eh)   ;; relative to list-ctr (which scrolls inside list)
            active? (is-active? tut)
            title (or (:title (tut-content/load-tutorial-content lang (:id tut)))
                      (name (:id tut)))
            ew (cgui-core/create-widget :pos [0.0 y] :size [liw eh])]
        ;; Text label with 3px indent matching upstream box.xOffset = 3
        (comp/add-component! ew
          (comp/text-box :text title :font-size 10.0
                         :color (if active? learned-color unlearned-color)
                         :align :left :height-align :center
                         :x-offset 3.0))
        ;; Tint component (idle transparent). Hover toggled via on-frame below.
        (comp/add-component! ew (comp/tint 0x00000000))
        (events/on-left-click ew
          (make-entry-click-handler root tut player-uuid lang content-ctr ui))
        (cgui-core/add-widget! list-ctr ew)
        (swap! entry-widgets conj ew)))
    (cgui-core/add-widget! list-w list-ctr)
    ;; Mousewheel scroll for list (same pattern as center panel)
    (let [on-scroll (fn [evt]
                      (let [delta-y (- (:delta-y evt))
                            new-y (max 0.0 (min max-scroll (+ @list-scroll-y (* delta-y 40.0))))]
                        (reset! list-scroll-y new-y)
                        (cgui-core/set-pos! list-ctr 0.0 (- new-y))))]
      (events/on-mouse-scroll list-w on-scroll))
    ;; Hover detection: on-frame on list-ctr checks mouse position against
    ;; entry bounds and toggles tint (matching upstream ElementList Tint hover).
    ;; All values are in screen-pixel space (multiplied by ui-scale).
    (events/on-frame list-ctr
      (fn [_]
        (let [mx (- (get @(:metadata root) :last-mouse-x -1) ctr-root-x)
              my (- (+ (get @(:metadata root) :last-mouse-y -1) (* ui-scale @list-scroll-y)) ctr-root-y)
              new-idx (if (and (>= mx 0) (< mx liw-px)
                              (>= my 0) (< my total-h-px))
                        (int (/ my eh-px))
                        -1)]
          (when (not= new-idx @hovered-idx)
            ;; Deactivate old hover
            (when (and (>= @hovered-idx 0) (< @hovered-idx (count @entry-widgets)))
              (when-let [t (comp/get-tint-component (nth @entry-widgets @hovered-idx))]
                (swap! (:state t) assoc :color 0x00000000)))
            ;; Activate new hover (30% white ≈ 0x4C alpha)
            (when (>= new-idx 0)
              (when-let [t (comp/get-tint-component (nth @entry-widgets new-idx))]
                (swap! (:state t) assoc :color 0x4CFFFFFF)))
            (reset! hovered-idx new-idx)))))
    ;; Scroll bar: track + draggable thumb (matching upstream ElementList built-in
    ;; scroll bar). Only visible when content exceeds list area height.
    ;; For the default 13 entries (156px total) in a 207px list area, this stays
    ;; hidden. If entries are added later, the scroll bar appears automatically.
    (when (pos? max-scroll)
      (let [sbw 4.0                                          ;; scroll bar width (logical)
            sb-x (- liw sbw)                                 ;; right-aligned in list area
            sb-h lih                                         ;; track height = list height
            track (cgui-core/create-widget :pos [sb-x 0.0] :size [sbw sb-h])
            ;; Thumb height proportional to visible/total ratio (min 12px)
            thumb-h (max 12.0 (* sb-h (/ lih total-h)))
            thumb-travel (- sb-h thumb-h)
            thumb (cgui-core/create-widget :pos [0.0 0.0] :size [sbw thumb-h])
            ;; Sync thumb Y to current scroll-y
            update-thumb! (fn []
                            (let [progress (/ @list-scroll-y max-scroll)
                                  thumb-y (* progress thumb-travel)]
                              (cgui-core/set-pos! thumb 0.0 thumb-y)))]
        ;; Track: faint semi-transparent background; Thumb: brighter handle
        (comp/add-component! track (comp/draw-texture nil 0x30FFFFFF))
        (comp/add-component! thumb (comp/draw-texture nil 0x80FFFFFF))
        (cgui-core/add-widget! track thumb)
        (cgui-core/add-widget! list-w track)
        ;; Initial thumb position
        (update-thumb!)
        ;; Keep thumb synced when scrolling via mouse wheel
        (events/on-frame list-w (fn [_] (update-thumb!)))
        ;; Drag thumb to scroll (dy in screen pixels → convert to logical)
        (events/on-drag thumb
          (fn [evt]
            (let [dy-logical (/ (double (:dy evt 0.0)) ui-scale)
                  [_ cy] (cgui-core/get-pos thumb)
                  new-cy (max 0.0 (min thumb-travel (+ cy dy-logical)))
                  progress (/ new-cy thumb-travel)
                  new-scroll-y (* progress max-scroll)]
              (cgui-core/set-pos! thumb 0.0 new-cy)
              (reset! list-scroll-y new-scroll-y)
              (cgui-core/set-pos! list-ctr 0.0 (- new-scroll-y)))))))))

(defn- make-entry-click-handler
  [root tut player-uuid lang content-ctr ui]
  (let [{:keys [current-tut-id scroll-y max-scroll scroll-progress
                pvs preview-item preview-type anim-start]} ui]
    (fn [_]
      (when (not= @current-tut-id (:id tut))
        (let [active? (or (:default-installed? tut)
                          (try (client-state/is-activated? player-uuid (:id tut))
                               (catch Throwable _ false)))]
          (when (nil? @current-tut-id)
            ;; Upstream: blend(logos) fades+disposes logos over 0.3s.
            ;; Show panels immediately; logos fade out asynchronously.
            ;; Glow widgets are children of logo1 → hidden automatically when logo1 is hidden.
            (let [fade-start (atom (System/currentTimeMillis))]
              (setup-logo-fade-out! root fade-start ["logo0" "logo1" "logo2" "logo3"] 300))
            ;; Show children individually (not rightPart — logos inside need visibility)
            (let [cp (find-widget-recursive root "center-panel")
                  sw (find-widget-recursive root "showWindow")
                  rw (find-widget-recursive root "rightWindow")]
              (when cp (cgui-core/set-visible! cp active?))
              (when sw (cgui-core/set-visible! sw true))
              (when rw (cgui-core/set-visible! rw true))))
          (reset! current-tut-id (:id tut))
          (let [cd (tut-content/load-tutorial-content lang (:id tut))]
            (when-let [cp (find-widget-recursive root "center-panel")]
              (cgui-core/set-visible! cp active?))
            (reset! scroll-y 0.0)
            (reset! scroll-progress 0.0)
            (when-let [thumb (find-widget-recursive root "scroll-thumb")]
              (cgui-core/set-pos! thumb 0.0 scroll-thumb-min-y))
            (when active?
              (let [misaka-id (client-state/get-misaka-id player-uuid)
                    total-h (cached-render-content! content-ctr (:id tut) lang
                                                    (:content cd) misaka-id)]
                (reset! max-scroll (max 0.0 (+ (- total-h coh) 10.0)))))
            (reposition-content! content-ctr scroll-y)
          (reset! pvs (preview/create-preview-state (:id tut)))
          (refresh-preview! root pvs
                           :preview-item preview-item
                           :preview-type preview-type)
          (when-let [tw (find-widget-recursive root "title-text")]
            (when-let [tb (comp/get-textbox-component tw)]
              (comp/set-text! tb (or (:title cd) ""))))
          (when-let [bw (find-widget-recursive root "brief-text")]
            (render-brief-markdown! bw (:brief cd)
                                   (client-state/get-misaka-id player-uuid)))))))))

(defn- wire-center-panel!
  [root content-ctr first-open? ui]
  (let [{:keys [scroll-y max-scroll scroll-progress]} ui
        cp (find-widget-recursive root "center-panel")
        _ (swap! (:metadata cp) assoc :clip-children? true)
        thumb-travel (- scroll-thumb-max-y scroll-thumb-min-y)
        thumb (find-widget-recursive root "scroll-thumb")]
    (cgui-core/add-widget! cp content-ctr)
    (let [on-drag-fn (fn [evt]
                       (when (pos? @max-scroll)
                         (let [ui-scale (double (or @(:scale root) 1.0))
                               dy-logical (/ (double (:dy evt 0.0)) ui-scale)
                               scroll-delta (* (/ dy-logical thumb-travel) @max-scroll)]
                           (swap! scroll-y + scroll-delta)
                           (clamp-scroll! scroll-y @max-scroll)
                           (let [progress (/ @scroll-y @max-scroll)
                                 thumb-y (+ scroll-thumb-min-y (* progress thumb-travel))]
                             (cgui-core/set-pos! thumb 0.0 thumb-y)
                             (reset! scroll-progress progress)
                             (reposition-content! content-ctr scroll-y)))))]
      (events/on-drag thumb on-drag-fn)
      (when-let [track (find-widget-recursive root "scroll-track")]
        (events/on-drag track on-drag-fn)))
    (let [on-scroll (fn [evt]
                      (when (pos? @max-scroll)
                        ;; MC delta: positive=down. Negate for scroll-up convention, boost to 40px/notch
                        (let [new-y (+ @scroll-y (* (- (:delta-y evt)) 40.0))]
                          (reset! scroll-y (max 0.0 (min @max-scroll new-y)))
                          (let [progress (/ @scroll-y @max-scroll)
                                thumb-y (+ scroll-thumb-min-y (* progress thumb-travel))]
                            (cgui-core/set-pos! thumb 0.0 thumb-y)
                            (reposition-content! content-ctr scroll-y)))))]
      (events/on-mouse-scroll thumb on-scroll)
      (events/on-mouse-scroll cp on-scroll)   ;; scroll anywhere in content area
      (events/on-mouse-scroll content-ctr on-scroll)
      (events/on-mouse-scroll root on-scroll))    ;; also from anywhere
    (when first-open? (cgui-core/set-visible! cp false))))

(defn- wire-right-panel!
  [root pvs preview-item preview-type first-open?]
  (let [rp (find-widget-recursive root "right-panel")
        sw (find-widget-recursive root "showWindow")
        rw (find-widget-recursive root "rightWindow")]
    (when first-open?
      (cgui-core/set-visible! sw false)
      (cgui-core/set-visible! rw false))
    (let [refresh! #(refresh-preview! root pvs
                                  :preview-item preview-item
                                  :preview-type preview-type)]
      (when-let [bl (find-widget-recursive root "btn-left")]
        (events/on-left-click bl (fn [_] (preview/cycle-sub-view! pvs :prev) (refresh!))))
      (when-let [br (find-widget-recursive root "btn-right")]
        (events/on-left-click br (fn [_] (preview/cycle-sub-view! pvs :next) (refresh!)))))
    ;; Upstream: font.draw(title, 3, 3) inside the text widget (not rightWindow directly).
    ;; text widget is CENTER-aligned in rightWindow → title must be its child.
    (let [bw (find-widget-recursive root "brief-text")
          tw (cgui-core/create-widget :pos [3 3] :size [130 14])]
      ;; Clip overflowing brief content (matching upstream CGUI auto-scissor).
      ;; Without this, markdown segments extend past rightWindow's bottom border.
      (swap! (:metadata bw) assoc :clip-children? true)
      (comp/add-component! tw (comp/text-box :text "" :font-size 10.0 :color 0xFFFFFFFF))
      (cgui-core/set-name! tw "title-text")
      (cgui-core/add-widget! bw tw))))

(defn- build-glow-lines! [root]
  ;; Upstream: glow drawn in logo1's FrameEvent with glTranslated(width/2, height/2+15, 0).
  ;; As children of logo1, positions must be in logo1's UNSCALED space (div by 0.25).
  ;; lineglow(ln-ln2, ln, ht) → right line from x=200 to x=500 in logo1-local.
  ;; lineglow(-ln, -(ln-ln2), ht) → left line from x=-500 to x=-200.
  ;;
  ;; Uses gradient-fill component (banded fill() calls) matching upstream
  ;; ACRenderingHelper.drawGlow gradient effect.  The upstream lineSegment
  ;; solid line is rendered by the center-band opacity. Zero texture assets.
  (let [ls 0.25               ;; logo1 scale
        glow-h (/ 3.0 ls)     ;; 3px visual → 12px in logo1-local
        glow-center-local (/ (- glow-center-y logo1-abs-y) ls)  ;; ≈ 133.0
        glow-y (- glow-center-local (/ glow-h 2))
        ;; Center-opaque white → edge-transparent, matching upstream
        ;; drawGlow(..., GLOW_COLOR=Colors.white()) + lineSegment
        glow-center-color 0xCCFFFFFF
        glow-edge-color   0x00FFFFFF
        logo1-w (find-widget-recursive root "logo1")]
    (when logo1-w
      (let [gr (cgui-core/create-widget :pos [0 glow-y] :size [0 glow-h])]
        (comp/add-component! gr (comp/gradient-fill glow-center-color glow-edge-color))
        (cgui-core/set-name! gr "glow-right")
        (cgui-core/add-widget! logo1-w gr))
      (let [gl (cgui-core/create-widget :pos [0 glow-y] :size [0 glow-h])]
        (comp/add-component! gl (comp/gradient-fill glow-center-color glow-edge-color))
        (cgui-core/set-name! gl "glow-left")
        (cgui-core/add-widget! logo1-w gl)))))

(defn- setup-static-glow! [root]
  ;; Upstream non-firstOpen: static lineglow(ln-ln2, ln, ht) / lineglow(-ln, -(ln-ln2), ht)
  ;; inside logo1's FrameEvent. Coordinates in logo1-local unscaled space.
  (doseq [nm ["logo0" "logo2" "logo3"]]
    (when-let [lw (find-widget-recursive root nm)]
      (cgui-core/set-visible! lw false)))
  (let [ln     500.0
        ln2    300.0
        glow-h 12.0            ;; 3px visual / 0.25 scale
        logo1-half-w (/ 899.0 2.0)
        glow-center-local (/ (- glow-center-y logo1-abs-y) 0.25)
        glow-y (- glow-center-local (/ glow-h 2.0))
        right-start (+ logo1-half-w (- ln ln2))   ;; 449.5 + 200 = 649.5
        left-start  (- logo1-half-w ln)           ;; 449.5 - 500 = -50.5
        glow-width  ln2]                          ;; 300
    (when-let [gr (find-widget-recursive root "glow-right")]
      (cgui-core/set-pos! gr right-start glow-y)
      (cgui-core/set-size! gr glow-width glow-h))
    (when-let [gl (find-widget-recursive root "glow-left")]
      (cgui-core/set-pos! gl left-start glow-y)
      (cgui-core/set-size! gl glow-width glow-h))
    (apply-logo-alpha! root "logo1" 255)))

;; --- Main open! function ---

(defn open! [player]
  (log/info "Opening tutorial GUI")
  (let [player-uuid (uuid/player-uuid player)
        _ (client-state/ensure-client-state! player-uuid)
        ;; Force immediate sync (bypass 5s throttle) so misaka-id is ready before first click
        _ (net-client/send-to-server (tut-msg/msg-id :tutorial/request-sync) {}
                                    (fn [resp] (when resp (client-state/apply-sync! resp))))
        lang (tut-content/current-lang)
        ;; Load widget tree from tutorial.xml (matching original GuiTutorial)
        xml-path (modid/asset-path "guis" "tutorial.xml")
        doc (cgui-doc/read-xml xml-path)
        root (cgui-doc/get-widget doc "frame")
        entries (tut-registry/all-tutorials)
        first-open? (client-state/first-open? player-uuid)
        content-ctr (cgui-core/create-widget
                     :pos [cox coy] :size [cow 5000.0])
        ui {:current-tut-id   (atom nil)
            :scroll-y         (atom 0.0)
            :max-scroll       (atom 0.0)
            :scroll-progress  (atom 0.0)
            :list-scroll-y    (atom 0.0)
            :pvs              (atom (preview/create-preview-state :welcome))
            :preview-item     (atom nil)
            :preview-type     (atom :icon)
            :anim-start       (atom nil)}]
    ;; Rename XML widgets → match current code conventions
    (cgui-core/set-name! (find-widget-recursive root "leftPart") "left-panel")
    (cgui-core/set-name! (find-widget-recursive root "rightPart") "right-panel")
    (cgui-core/set-name! (find-widget-recursive root "centerPart") "center-panel")
    (cgui-core/set-name! (find-widget-recursive root "area") "preview-area")
    (cgui-core/set-name! (find-widget-recursive root "tag_area") "tag-area")
    (cgui-core/set-name! (find-widget-recursive root "btn_left") "btn-left")
    (cgui-core/set-name! (find-widget-recursive root "btn_right") "btn-right")
    (cgui-core/set-name! (find-widget-recursive root "scroll_1") "scroll-track")
    (cgui-core/set-name! (find-widget-recursive root "scroll_2") "scroll-thumb")
    (let [rw (find-widget-recursive root "rightWindow")]
      (cgui-core/set-name! (cgui-core/find-widget rw "text") "brief-text"))
    ;; Reorder scrollbar: XML has thumb BEFORE track → track covers thumb.
    ;; Move thumb to end of children so it renders on top of track.
    (let [cp (find-widget-recursive root "center-panel")
          thumb (find-widget-recursive root "scroll-thumb")]
      (cgui-core/remove-widget! cp thumb)
      (cgui-core/add-widget! cp thumb))
    ;; Initial visibility (matching original GuiTutorial __init lines 204-206, 350)
    ;; centerPart/showWindow/rightWindow always hidden; leftPart stays visible (blended in)
    (cgui-core/set-visible! (find-widget-recursive root "center-panel") false)
    (cgui-core/set-visible! (find-widget-recursive root "showWindow") false)
    (cgui-core/set-visible! (find-widget-recursive root "rightWindow") false)
    (when first-open?
      (when-let [list-w (find-widget-recursive root "list")]
        (cgui-core/set-visible! list-w false)))
    ;; Logo initial alpha=0
    (doseq [nm ["logo0" "logo1" "logo2" "logo3"]]
      (apply-logo-alpha! root nm 0))
    ;; Nav buttons init hidden
    (cgui-core/set-visible! (find-widget-recursive root "btn-left") false)
    (cgui-core/set-visible! (find-widget-recursive root "btn-right") false)
    ;; Programmatic widgets
    (build-background! root)
    (build-glow-lines! root)
    ;; Populate + wire
    (populate-left-panel! root entries lang first-open? content-ctr ui player-uuid)
    (wire-center-panel! root content-ctr first-open? ui)
    (wire-right-panel! root (:pvs ui) (:preview-item ui) (:preview-type ui) first-open?)
    ;; Animation
    (if first-open?
      (do (reset! (:anim-start ui) (System/currentTimeMillis))
          (setup-first-open-animation! root (:anim-start ui)))
      (setup-static-glow! root))
    ;; Hover detection for tag area — floating tooltip + tint transition
    ;; (matching original tagArea FrameEvent: font.draw(group.getDisplayText(), 0, -8))
    (let [hover-last-tag-idx (atom -1)
          ui-scale (double (or @(:scale root) 1.0))
          ;; Dynamic tag size from widget (matching original tagArea.transform.height)
          ta-widget (find-widget-recursive root "tag-area")
          [_ ta-h] (cgui-core/get-size ta-widget)
          tag-size   (* ui-scale (double ta-h))
          tag-step   (* ui-scale (- (double ta-h) 1.0))
          tag-base-x (* ui-scale (+ rx 12.0))
          tag-base-y (* ui-scale 120.75)]
      (events/on-frame root
        (fn [_]
          (let [mx (get @(:metadata root) :last-mouse-x -1)
                my (get @(:metadata root) :last-mouse-y -1)
                pvs (:pvs ui)
                {:keys [view-groups]} @pvs
                tag-count (count (or view-groups []))
                hover-idx (when (and (>= my tag-base-y)
                                     (< my (+ tag-base-y tag-size)))
                            (loop [i 0]
                              (when (< i tag-count)
                                (let [tx (+ tag-base-x (* i tag-step))]
                                  (if (and (>= mx tx) (< mx (+ tx tag-size)))
                                    i
                                    (recur (inc i)))))))
                tag-widgets (get @(:metadata root) :tag-widgets)
                tooltip-w (get @(:metadata root) :tag-tooltip)]
            (when (not= @hover-last-tag-idx hover-idx)
              ;; Restore previous tag tint to idle (70% white = 0xB3FFFFFF)
              (when (and (>= @hover-last-tag-idx 0)
                         (< @hover-last-tag-idx (count tag-widgets)))
                (when-let [t (comp/get-tint-component (nth tag-widgets @hover-last-tag-idx))]
                  (swap! (:state t) assoc :color 0xB3FFFFFF)))
              ;; Activate new hover tag tint to 100% white
              (when (and hover-idx (>= hover-idx 0) (< hover-idx (count tag-widgets)))
                (when-let [t (comp/get-tint-component (nth tag-widgets hover-idx))]
                  (swap! (:state t) assoc :color 0xFFFFFFFF)))
              ;; Show/hide floating tooltip above the hovered tag
              (if (and hover-idx (>= hover-idx 0) (< hover-idx tag-count) tooltip-w)
                (let [display-text (or (:display-text (nth view-groups hover-idx)) "")
                      ;; Tooltip position in tag-area-local logical coords
                      ;; (handled by widget tree — tooltip is a child of tag-area)
                      tt-x (* hover-idx (/ tag-step ui-scale))]
                  (when-let [tt-tb (comp/get-textbox-component tooltip-w)]
                    (comp/set-text! tt-tb display-text))
                  (cgui-core/set-pos! tooltip-w tt-x (- (+ (/ tag-size ui-scale) 4.0)))
                  (cgui-core/set-visible! tooltip-w true))
                (when tooltip-w
                  (cgui-core/set-visible! tooltip-w false)))
              (reset! hover-last-tag-idx hover-idx))))))
    (client-bridge/open-simple-gui! root "MisakaCloud Terminal"
      {:interactive? true
       :ref-width 480.0   ;; match original AcademyCraft REF_WIDTH = 480
       :preview-item-atom (:preview-item ui)
       :preview-type-atom  (:preview-type ui)})))
