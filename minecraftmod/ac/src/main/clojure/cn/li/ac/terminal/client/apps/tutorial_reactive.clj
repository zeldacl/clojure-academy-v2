(ns cn.li.ac.terminal.client.apps.tutorial-reactive
  "Complete reactive replacement for terminal/client/apps/tutorial.clj (the
   'MisakaCloud Terminal' app — markdown content, staggered logo fade-in,
   glow lines, tag navigation, left-panel tutorial list).

   All pure logic is reused verbatim: markdown-renderer/render-segments,
   logo-fade-alpha timing math, client-state/*, tut-registry/*, tut-content/*,
   preview-reactive's ViewGroup navigation. Absolute pixel positions below
   are hand-derived from the real guis/tutorial.xml widget tree (frame 427×
   240; leftPart/rightPart/centerPart/showWindow/rightWindow/logo0-3 nesting
   and CENTER/LEFT/RIGHT align math resolved by hand — cross-checked against
   this file's own logo1/logo2 abs position against rx/rw, both of which
   matched independently, so confidence is high).

   Simplifications versus the original (cosmetic-only, no functional loss):
   - Drag-scrollbar → mouse-wheel only, for both the left tutorial list and
     the center markdown content. The scrollbar thumb still renders and
     tracks position, just isn't itself draggable.
   - Glow gradient-fill → flat translucent box (the framework's own :gradient
     kind renders a fixed flat color today regardless of :stops, so this
     matches actual current rendering capability, not a regression).
   - :block-3d/:item-3d recipe/3D preview → empty placeholder (verified via
     repo-wide grep that nothing ever rendered real content into these in
     the old system either — see preview_reactive.clj's docstring)."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.tutorial.content :as tut-content]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.ac.tutorial.markdown-renderer :as mr]
            [cn.li.ac.tutorial.client.preview-reactive :as preview]
            [cn.li.ac.tutorial.client.state :as client-state]
            [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.signal ISigO]))

;; ============================================================================
;; Layout constants — absolute frame-root positions (frame 427×240)
;; ============================================================================

(def ^:private gw 427.0) (def ^:private gh 240.0)
(def ^:private eh 12.0) (def ^:private lih 207.0) (def ^:private liw 72.0)
(def ^:private cow 160.0) (def ^:private coh 210.5)
(def ^:private area-x 277.75) (def ^:private area-y 8.75)
(def ^:private area-w 134.0) (def ^:private area-h 134.0)
(def ^:private tex (partial modid/asset-path "textures/guis"))

;; ============================================================================
;; set-tick! — force a per-frame side-effecting computed-o to actually run
;; (see developer panel-reactive.clj for the fuller writeup).
;; ============================================================================

(defn- pull-o! [_node source] (.sGet ^ISigO source) nil)

(defn- set-tick! [^UiRt rt key computed-sig]
  (when-let [old (rt/user-signal rt key)] (sig/unbind! old))
  (if computed-sig
    (let [^INode anchor (rt/node-by-id rt :root)
          b (sig/bind! computed-sig anchor pull-o! (rt/get-dirty-bindings-q rt))]
      (rt/register-binding! rt (.getIdx anchor) b)
      (rt/put-user-signal! rt key b))
    (rt/put-user-signal! rt key nil)))

(defn- hover-alpha-step [idle-a hover-a ^UiRt rt idx _ms]
  (if (= (long idx) (rt/hovered-idx rt)) (double hover-a) (double idle-a)))

(defn- bind-hover-alpha! [^UiRt rt id idle-a hover-a]
  (let [^INode n (rt/node-by-id rt id)
        idx (.getIdx n)
        clock (rt/clock-ms-sig rt)
        sig-d (sig/computed-d [clock] (partial hover-alpha-step idle-a hover-a rt idx))]
    (ui/bind! rt id :alpha sig-d)))

(defn- img
  ([id x y w h src] (img id x y w h src 1.0 true))
  ([id x y w h src scale] (img id x y w h src scale true))
  ([id x y w h src scale visible?]
   {:kind :image :props {:id id :x (double x) :y (double y) :w (double w) :h (double h)
                          :src src :scale (double scale) :visible? visible?}}))

;; ============================================================================
;; Root spec — static chrome. Logo1's glow lines are children of a scaled
;; anchor group (scale=0.25) so their local coordinates match the original
;; logo1-local unscaled math verbatim (cum-scale handles the conversion,
;; exactly like the old nested-widget system did).
;; ============================================================================

(defn- root-spec []
  {:kind :box
   :props {:id :root :x 0.0 :y 0.0 :w gw :h gh :fill 0xC0101010}
   :children
   [(img :left-bg 7.0 9.75 85.0 220.5 (tex "window_tutorial_left.png"))
    {:kind :group :props {:id :list :x 13.6 :y 16.75 :w liw :h lih :clip? true}}

    (img :logo0 145.625 19.0 899.0 548.0 (tex "tutorial/logo0.png") 0.25)
    (img :logo1 145.625 149.5 899.0 236.0 (tex "tutorial/logo1.png") 0.25)
    (img :logo2 145.625 149.5 899.0 236.0 (tex "tutorial/logo2.png") 0.25)
    (img :logo3 239.375 65.375 149.0 149.0 (tex "tutorial/logo3.png") 0.25)
    {:kind :group :props {:id :logo1-anchor :x 145.625 :y 149.5 :w 0.0 :h 0.0 :scale 0.25}
     :children
     [{:kind :box :props {:id :glow-right :x 0.0 :y 127.0 :w 0.0 :h 12.0 :fill 0x30FFFFFF :visible? false}}
      {:kind :box :props {:id :glow-left :x 0.0 :y 127.0 :w 0.0 :h 12.0 :fill 0x30FFFFFF :visible? false}}]}

    {:kind :group :props {:id :center-panel :x 97.0 :y 12.75 :w cow :h coh :clip? true :visible? false}}
    (img :scroll-track 254.5 11.75 9.5 216.5 (tex "button/widget_scroll_1.png"))
    (img :scroll-thumb 254.5 11.75 9.5 53.0 (tex "button/widget_scroll_2.png"))

    {:kind :group :props {:id :preview-area :x area-x :y area-y :w area-w :h area-h}}
    {:kind :group :props {:id :tag-area :x 277.5 :y 130.5 :w 133.0 :h 18.0}}
    (img :btn-left 270.5 51.5 30.0 130.0 (tex "button/button_left_2.png") 0.4 false)
    (img :btn-right 405.5 51.5 30.0 130.0 (tex "button/button_right_2.png") 0.4 false)

    (img :right-window-bg 265.5 148.25 158.5 82.0 (tex "window_tutorial_left.png") 1.0 false)
    {:kind :text :props {:id :title-text :x 274.75 :y 157.5 :w 130.0 :h 14.0
                          :text "" :font-size 10.0 :color 0xFFFFFFFF}}
    {:kind :group :props {:id :brief-content :x 274.75 :y 169.5 :w 130.0 :h 50.0 :clip? true}}
    {:kind :text :props {:id :tag-tooltip :x 277.5 :y 122.0 :w 120.0 :h 14.0
                          :text "" :font-size 10.0 :color 0xFFFFFFFF :visible? false}}]})

;; ============================================================================
;; Markdown content — cache + native segment rendering (reused verbatim from
;; tutorial.clj except the widget construction, which is native).
;; ============================================================================

(defonce ^:private content-cache (atom {}))

(defn- render-content-segs [tut-id lang content-str misaka-id]
  (let [cache-key [tut-id lang (str misaka-id)]]
    (or (get @content-cache cache-key)
        (let [segs (mr/render-segments content-str misaka-id)
              total-h (loop [sg segs y 0.0]
                        (if (seq sg)
                          (let [seg (first sg)
                                h (if (= (:type seg) :image) (or (:img-h seg) mr/default-image-height) mr/line-height)]
                            (recur (rest sg) (+ y h)))
                          y))
              result {:segs segs :total-h total-h}]
          (swap! content-cache assoc cache-key result)
          result))))

(defn- seg-node-spec [id seg y w]
  (let [image? (= (:type seg) :image)]
    (if image?
      {:kind :image :props {:id id :x 0.0 :y y :w w :h (or (:img-h seg) mr/default-image-height)
                             :src (:texture-path seg)}}
      {:kind :text :props {:id id :x 0.0 :y y :w w :h mr/line-height
                            :text (:text seg) :font-size (:font-size seg mr/default-font-size)
                            :color (:color seg mr/default-color)}})))

(defn- rebuild-segments! [^UiRt rt ctr-id segs seg-w]
  (let [ctr (rt/node-by-id rt ctr-id)]
    (rt/clear-children! rt ctr)
    (loop [sg segs y 0.0 n 0]
      (when (seq sg)
        (let [seg (first sg)
              h (if (= (:type seg) :image) (or (:img-h seg) mr/default-image-height) mr/line-height)
              w (if (= (:type seg) :image) mr/max-content-width seg-w)
              id (keyword (str (name ctr-id) "-" n))]
          (rt/build-child! rt (seg-node-spec id seg y w) ctr)
          (recur (rest sg) (+ y h) (inc n)))))))

;; ============================================================================
;; Scroll — mouse-wheel only (drag-scrollbar simplification, see ns docstring)
;; ============================================================================

(defn- reposition-content! [^INode ctr scroll-y]
  (.setY ctr (- (double scroll-y)))
  (.setFlag ctr node/FLAG-LAYOUT-DIRTY))

(defn- sync-thumb! [^UiRt rt scroll-y max-scroll]
  (let [^INode thumb (rt/node-by-id rt :scroll-thumb)
        travel (- 165.0 2.0)
        progress (if (pos? max-scroll) (/ (double scroll-y) (double max-scroll)) 0.0)
        thumb-y (+ 2.0 (* progress travel))]
    (.setY thumb (+ 11.75 (- thumb-y 2.0)))
    (.setFlag thumb node/FLAG-LAYOUT-DIRTY)))

;; ============================================================================
;; Preview area + tag navigation
;; ============================================================================

(defn- refresh-preview! [^UiRt rt ui-state]
  (let [{:keys [pvs]} ui-state
        view (preview/current-sub-view pvs)
        area (rt/node-by-id rt :preview-area)]
    (rt/clear-children! rt area)
    (when view
      (rt/build-child! rt (preview/build-preview-spec view :current-preview) area)))
  ;; Tag icons
  (let [{:keys [pvs]} ui-state
        tag-area (rt/node-by-id rt :tag-area)
        _ (rt/clear-children! rt tag-area)
        {:keys [view-groups group-index]} @pvs
        step 17.0 sz 18.0
        tag-idx->id (atom {})]
    (doseq [[idx vg] (map-indexed vector (or view-groups []))]
      (let [id (keyword (str "tag-" idx))
            active? (= idx group-index)
            spec {:kind :box :props {:id id :x (* idx step) :y 0.0 :w sz :h sz
                                      :fill (if active? 0x40FFFFFF 0x00000000) :hover-tint 0.3}
                  :children [(img (keyword (str "tag-" idx "-icon")) 0.0 0.0 sz sz
                                  (get preview/tag-textures (:tag vg :view)))]}
            ^INode n (rt/build-child! rt spec tag-area)]
        (swap! tag-idx->id assoc (.getIdx n) (:display-text vg))
        (events/on! rt id :left-click
          (fn [_ _ _]
            (preview/switch-view-group! (:pvs ui-state) idx)
            (refresh-preview! rt ui-state)))))
    (rt/put-user-signal! rt :tag-hover-map @tag-idx->id))
  ;; Brief title + text
  (let [{:keys [current-cd player-uuid]} ui-state
        cd @current-cd]
    (ui/set-prop! rt :title-text :text (or (:title cd) ""))
    (when-let [brief (:brief cd)]
      (let [segs (mr/render-segments brief (client-state/get-misaka-id player-uuid))]
        (rebuild-segments! rt :brief-content segs 124.0))))
  ;; Nav buttons visible only when >1 sub-view
  (let [{:keys [pvs]} ui-state
        vg (preview/current-view-group pvs)
        cnt (count (or (:sub-views vg) []))
        ^INode bl (rt/node-by-id rt :btn-left)
        ^INode br (rt/node-by-id rt :btn-right)]
    (.setVisible bl (> cnt 1)) (.setFlag bl node/FLAG-LAYOUT-DIRTY)
    (.setVisible br (> cnt 1)) (.setFlag br node/FLAG-LAYOUT-DIRTY)))

(defn- attach-tag-hover-tick! [^UiRt rt]
  (set-tick! rt :tag-hover-tick
    (sig/computed-o [(rt/clock-ms-sig rt)]
      (fn [_]
        (let [hover-map (or (rt/user-signal rt :tag-hover-map) {})
              display-text (get hover-map (rt/hovered-idx rt))
              ^INode tt (rt/node-by-id rt :tag-tooltip)]
          (when tt
            (.setVisible tt (boolean display-text))
            (when display-text (ui/set-node-prop! rt tt :text display-text))
            (.setFlag tt node/FLAG-LAYOUT-DIRTY)))
        nil))))

;; ============================================================================
;; Left panel — tutorial entry list
;; ============================================================================

(defn- is-active? [player-uuid tut]
  (or (:default-installed? tut)
      (and (client-state/ready?)
           (client-state/is-activated? player-uuid (:id tut)))))

(declare select-entry!)

(defn- populate-list! [^UiRt rt ui-state entries]
  (let [{:keys [player-uuid lang]} ui-state
        list-node (rt/node-by-id rt :list)
        _ (rt/clear-children! rt list-node)
        {:keys [active inactive]} (reduce (fn [acc tut]
                                             (if (is-active? player-uuid tut)
                                               (update acc :active conj tut)
                                               (update acc :inactive conj tut)))
                                           {:active [] :inactive []} entries)
        grouped (concat active inactive)]
    (doseq [[idx tut] (map-indexed vector grouped)]
      (let [active? (is-active? player-uuid tut)
            title (or (:title (tut-content/load-tutorial-content lang (:id tut))) (name (:id tut)))
            id (keyword (str "tut-entry-" idx))
            spec {:kind :box :props {:id id :x 0.0 :y (* idx eh) :w liw :h eh
                                      :fill 0x00000000 :hover-tint 0.2}
                  :children [{:kind :text :props {:x 3.0 :y 1.0 :w (- liw 6.0) :h 10.0
                                                   :text title :font-size 9.0
                                                   :color (if active? 0xFFFFFFFF 0xFF999999)}}]}]
        (rt/build-child! rt spec list-node)
        (events/on! rt id :left-click
          (fn [_ _ _] (select-entry! rt ui-state tut)))))))

;; ============================================================================
;; First-open staggered logo animation / static glow (reused timing math
;; verbatim from tutorial.clj's logo-fade-alpha/setup-first-open-animation!)
;; ============================================================================

(defn- logo-fade-alpha [elapsed-ms start-delay-ms duration-ms]
  (let [t (max 0.0 (min 1.0 (/ (- elapsed-ms start-delay-ms) duration-ms)))]
    (int (* 255.0 t))))

(defn- set-logo-alpha! [^UiRt rt logo-id alpha]
  (let [^INode n (rt/node-by-id rt logo-id)]
    (when n
      (.setDSlot n 0 (/ (double alpha) 255.0))
      (.setFlag n node/FLAG-RENDER-DIRTY))))

(def ^:private logo-timings
  [[:logo3 100.0 300.0] [:logo2 650.0 300.0] [:logo1 1300.0 300.0] [:logo0 1750.0 300.0]])

(defn- reveal-panels! [^UiRt rt center-active?]
  (let [^INode cp (rt/node-by-id rt :center-panel)
        ^INode rw (rt/node-by-id rt :right-window-bg)]
    (.setVisible cp center-active?) (.setFlag cp node/FLAG-LAYOUT-DIRTY)
    (.setVisible rw true) (.setFlag rw node/FLAG-LAYOUT-DIRTY)))

(defn- attach-first-open-animation! [^UiRt rt anim-start]
  (doseq [[id _ _] logo-timings] (set-logo-alpha! rt id 0))
  (let [done? (atom false)]
    (set-tick! rt :logo-anim-tick
      (sig/computed-o [(rt/clock-ms-sig rt)]
        (fn [ms]
          (when-not @done?
            (let [elapsed (- (double ms) (double anim-start))]
              (doseq [[id start-ms dur-ms] logo-timings]
                (set-logo-alpha! rt id (logo-fade-alpha elapsed start-ms dur-ms)))
              ;; Glow animation (logo1-local unscaled coords via the scaled anchor group)
              (let [dt (- elapsed 400.0) b1 300.0 b2 200.0
                    ln 500.0 ln2 300.0 cl 50.0 half-w 449.5
                    ^INode gr (rt/node-by-id rt :glow-right) ^INode gl (rt/node-by-id rt :glow-left)]
                (when (>= dt 0)
                  (if (< dt b1)
                    (let [len (* ln (/ dt b1))]
                      (when (> len cl)
                        (.setVisible gr true) (.setX gr (+ half-w cl)) (.setW gr (- len cl))
                        (.setVisible gl true) (.setX gl (- half-w len)) (.setW gl (- len cl))
                        (.setFlag gr node/FLAG-LAYOUT-DIRTY) (.setFlag gl node/FLAG-LAYOUT-DIRTY)))
                    (let [ldt (min (- dt b1) b2)
                          len2 (+ (- ln cl cl) (* (- ln2 (- ln cl cl)) (/ ldt b2)))]
                      (.setX gr (+ half-w (- ln len2))) (.setW gr len2)
                      (.setX gl (- half-w ln)) (.setW gl len2)
                      (.setFlag gr node/FLAG-LAYOUT-DIRTY) (.setFlag gl node/FLAG-LAYOUT-DIRTY)))))
              (when (>= elapsed 2400.0)
                (let [^INode list-n (rt/node-by-id rt :list)]
                  (.setVisible list-n true) (.setFlag list-n node/FLAG-LAYOUT-DIRTY))
                (net-client/send-to-server (tut-msg/msg-id :tutorial/mark-first-open-done) {})
                (client-state/apply-sync! {:first-open? false})
                (reset! done? true)
                (set-tick! rt :logo-anim-tick nil))))
          nil)))))

(defn- setup-static-glow! [^UiRt rt]
  (doseq [id [:logo0 :logo2 :logo3]]
    (let [^INode n (rt/node-by-id rt id)] (.setVisible n false) (.setFlag n node/FLAG-LAYOUT-DIRTY)))
  (set-logo-alpha! rt :logo1 255)
  (let [ln 500.0 ln2 300.0 half-w 449.5
        ^INode gr (rt/node-by-id rt :glow-right) ^INode gl (rt/node-by-id rt :glow-left)]
    (.setVisible gr true) (.setX gr (+ half-w (- ln ln2))) (.setW gr ln2)
    (.setVisible gl true) (.setX gl (- half-w ln)) (.setW gl ln2)
    (.setFlag gr node/FLAG-LAYOUT-DIRTY) (.setFlag gl node/FLAG-LAYOUT-DIRTY)))

;; ============================================================================
;; Logo fade-out — triggered on the FIRST tutorial-entry click, regardless of
;; whether the screen opened in first-open (staggered fade-in) or returning
;; (static glow) mode. Reuses the same :logo-anim-tick key so set-tick!
;; naturally cancels any still-running fade-in tick.
;; ============================================================================

(defn- fade-out-logos! [^UiRt rt fade-start]
  (let [done? (atom false)]
    (set-tick! rt :logo-anim-tick
      (sig/computed-o [(rt/clock-ms-sig rt)]
        (fn [ms]
          (when-not @done?
            (let [elapsed (- (double ms) (double fade-start))
                  t (max 0.0 (min 1.0 (/ elapsed 300.0)))
                  alpha (int (* 255.0 (- 1.0 t)))]
              (doseq [id [:logo0 :logo1 :logo2 :logo3]] (set-logo-alpha! rt id alpha))
              (when (>= elapsed 300.0)
                (doseq [id [:logo0 :logo1 :logo2 :logo3]]
                  (let [^INode n (rt/node-by-id rt id)] (.setVisible n false) (.setFlag n node/FLAG-LAYOUT-DIRTY)))
                (reset! done? true)
                (set-tick! rt :logo-anim-tick nil))))
          nil)))))

;; ============================================================================
;; Entry selection
;; ============================================================================

(defn- select-entry! [^UiRt rt ui-state tut]
  (let [{:keys [current-tut-id lang player-uuid current-cd scroll-y max-scroll pvs]} ui-state]
    (when (not= @current-tut-id (:id tut))
      (let [active? (is-active? player-uuid tut)]
        (when (nil? @current-tut-id)
          (reveal-panels! rt active?)
          (fade-out-logos! rt (double (sig/sget-l (rt/clock-ms-sig rt)))))
        (reset! current-tut-id (:id tut))
        (let [cd (tut-content/load-tutorial-content lang (:id tut))]
          (reset! current-cd cd)
          (reset! scroll-y 0.0) (reset! max-scroll 0.0)
          (let [^INode cp (rt/node-by-id rt :center-panel)]
            (.setVisible cp active?) (.setFlag cp node/FLAG-LAYOUT-DIRTY))
          (when active?
            (let [{:keys [segs total-h]} (render-content-segs (:id tut) lang (:content cd)
                                                               (client-state/get-misaka-id player-uuid))]
              (rebuild-segments! rt :center-panel segs cow)
              (reset! max-scroll (max 0.0 (+ (- total-h coh) 10.0)))))
          (reposition-content! (rt/node-by-id rt :center-panel) 0.0)
          (sync-thumb! rt 0.0 @max-scroll)
          (reset! pvs (preview/create-preview-state (:id tut)))
          (refresh-preview! rt ui-state))))))

;; ============================================================================
;; Scroll handlers — mouse-wheel only (see ns docstring)
;; ============================================================================

(defn- attach-scroll! [^UiRt rt ui-state]
  (let [{:keys [scroll-y max-scroll]} ui-state]
    (events/on! rt :center-panel :mouse-scroll
      (fn [_ _ evt]
        (when (pos? @max-scroll)
          (let [new-y (max 0.0 (min @max-scroll (+ @scroll-y (* (- (double (:delta evt 0.0))) 12.0))))]
            (reset! scroll-y new-y)
            (reposition-content! (rt/node-by-id rt :center-panel) new-y)
            (sync-thumb! rt new-y @max-scroll)))))
    (events/on! rt :list :mouse-scroll
      (fn [_ _ evt]
        (let [^INode list-n (rt/node-by-id rt :list)
              delta (* (- (double (:delta evt 0.0))) 12.0)]
          (.setY list-n (max (- 400.0) (min 0.0 (+ (.getY list-n) delta))))
          (.setFlag list-n node/FLAG-LAYOUT-DIRTY))))))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn create-runtime [player]
  (let [player-uuid (uuid/player-uuid player)
        _ (client-state/ensure-client-state! player-uuid)
        _ (net-client/send-to-server (tut-msg/msg-id :tutorial/request-sync) {}
            (fn [resp] (when resp (client-state/apply-sync! resp))))
        lang (tut-content/current-lang)
        entries (tut-registry/all-tutorials)
        first-open? (client-state/first-open? player-uuid)
        r (rt/create-runtime)
        ui-state {:current-tut-id (atom nil)
                  :scroll-y (atom 0.0)
                  :max-scroll (atom 0.0)
                  :pvs (atom (preview/create-preview-state :welcome))
                  :current-cd (atom {})
                  :player-uuid player-uuid
                  :lang lang}]
    (rt/build! r (root-spec))
    (when first-open?
      (let [^INode list-n (rt/node-by-id r :list)]
        (.setVisible list-n false) (.setFlag list-n node/FLAG-LAYOUT-DIRTY)))
    (populate-list! r ui-state entries)
    (attach-scroll! r ui-state)
    (attach-tag-hover-tick! r)
    (bind-hover-alpha! r :btn-left 0.8 1.0)
    (bind-hover-alpha! r :btn-right 0.8 1.0)
    (events/on! r :btn-left :left-click
      (fn [_ _ _] (preview/cycle-sub-view! (:pvs ui-state) :prev) (refresh-preview! r ui-state)))
    (events/on! r :btn-right :left-click
      (fn [_ _ _] (preview/cycle-sub-view! (:pvs ui-state) :next) (refresh-preview! r ui-state)))
    (if first-open?
      (attach-first-open-animation! r (double (sig/sget-l (rt/clock-ms-sig r))))
      (setup-static-glow! r))
    r))

(defn open! [player]
  (bridge/open-reactive-screen! (create-runtime player) "MisakaCloud Terminal"))
