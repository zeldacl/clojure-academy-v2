(ns cn.li.ac.terminal.client.apps.tutorial-reactive
  "Tutorial catalogue and markdown content on Presentation Runtime.

   Tutorial navigation, recipe expansion, tag paging, preview projection and
   first-open logo animation remain AC-owned; the artifact and input lifecycle
   are owned by Presentation Runtime."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [clojure.string :as str]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.ac.tutorial.client.preview-reactive :as preview]
            [cn.li.ac.tutorial.client.state :as client-state]
            [cn.li.ac.tutorial.content :as tut-content]
            [cn.li.ac.tutorial.markdown-renderer :as markdown]
            [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]))

(def ^:private brief-width 130)
(def ^:private logo3-layout-y 55.625)
(def ^:private logo3-start-y 63.0)   ;; unscaled XML offset during blendy
(def ^:private logo3-final-y -36.0)

;; Tag tooltip geometry — mirrors tutorial.ui.edn absolute tree + main step/offset.
;; right-panel(92,9.75) + show-window(173.5,0) + tag-area(12,120.75); step 17.
;; (preview pane is at y=-1; tags stay at show-window local y=120.75.)
(def ^:private tag-abs-x0 277.5)
(def ^:private tag-abs-y0 130.5)
(def ^:private tag-step 17.0)
(def ^:private tag-tooltip-y-offset -8.0)

;; logo1-anchor scale 0.25 — glow dslots are screen-pixel offsets from logo1 center.
;; Glow repeater in tutorial.ui.edn is placed at that center; items use offsets only.
(def ^:private glow-s 0.25)
(def ^:private glow-ln 500.0)
(def ^:private glow-ln2 300.0)
(def ^:private glow-cl 50.0)

(def ^:private logo-timings
  [[:logo3 100.0 300.0] [:logo2 650.0 300.0] [:logo1 1300.0 300.0] [:logo0 1750.0 300.0]])

(defonce ^:private screen-tick* (atom nil))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- logo-fade-alpha [elapsed-ms start-delay-ms duration-ms]
  (let [t (max 0.0 (min 1.0 (/ (- elapsed-ms start-delay-ms) duration-ms)))]
    (float t)))

(defn- alpha->rgba [a]
  [1.0 1.0 1.0 (float (max 0.0 (min 1.0 (double a))))])

(defn- static-glow-geom
  "Final/static glow endpoints — matches main setup-static-glow!."
  []
  (let [s glow-s ln glow-ln ln2 glow-ln2]
    {:glow-visible? true
     :glow-right-x0 (* s (- ln ln2))
     :glow-right-x1 (* s ln)
     :glow-left-x0 (* s (- ln))
     :glow-left-x1 (* s (- ln2 ln))
     :glow-y (* s 15.0)
     :glow-line-w (max 1.0 (* s 5.0))
     :glow-sz (max 1.0 (* s 5.0))}))

(def ^:private glow-textures
  {:lu "academy:textures/guis/glow_lu.png"
   :ru "academy:textures/guis/glow_ru.png"
   :ld "academy:textures/guis/glow_ld.png"
   :rd "academy:textures/guis/glow_rd.png"
   :l  "academy:textures/guis/glow_left.png"
   :r  "academy:textures/guis/glow_right.png"
   :u  "academy:textures/guis/glow_up.png"
   :d  "academy:textures/guis/glow_down.png"
   :line "academy:textures/guis/line.png"})

(defn- glow-quad-item [src x0 y0 x1 y1]
  (let [w (float (- x1 x0))
        h (float (- y1 y0))]
    (when (and (> (Math/abs w) 0.01) (> (Math/abs h) 0.01))
      {:kind :image
       :src src
       ;; CompositeSpec offsets are relative to the glow repeater's origin
       ;; (logo1 center). Normalize inverted rects so w/h stay positive.
       :x (float (min x0 x1)) :y (float (min y0 y1))
       :w (float (Math/abs w)) :h (float (Math/abs h))
       :rgba [1.0 1.0 1.0 1.0]})))

(defn- glow-segment-items
  "One horizontal glow beam (main render-glow-line! / ACRenderingHelper.drawGlow).
   Coordinates are offsets from logo1-anchor center (glow repeater origin)."
  [x0 x1 gy line-w glow-sz]
  (let [gx0 (float x0)
        gx1 (float x1)
        gy (float gy)
        s (float (max 1.0 glow-sz))
        hw (float (/ (max 1.0 line-w) 2.0))
        glx0 (- gx0 s) glx1 (+ gx1 s)
        gly0 (- gy s) gly1 (+ gy s)
        gy0 (- gy hw) gy1 (+ gy hw)
        t glow-textures]
    (when (> (Math/abs (- gx1 gx0)) 0.5)
      (vec (keep identity
                 [(glow-quad-item (:lu t) glx0 gly0 gx0 gy0)
                  (glow-quad-item (:ru t) gx1 gly0 glx1 gy0)
                  (glow-quad-item (:ld t) glx0 gy1 gx0 gly1)
                  (glow-quad-item (:rd t) gx1 gy1 glx1 gly1)
                  (glow-quad-item (:l t) glx0 gy0 gx0 gy1)
                  (glow-quad-item (:r t) gx1 gy0 glx1 gy1)
                  (glow-quad-item (:u t) gx0 gly0 gx1 gy0)
                  (glow-quad-item (:d t) gx0 gy1 gx1 gly1)
                  (glow-quad-item (:line t) gx0 gy0 gx1 gy1)])))))

(defn- glow-items
  "Rasterize both glow beams into composite IMAGE items (glow-line primitive
   currently lowers to an empty nine-slice — known schema gap).
   Repeater is anchored at logo1 center (see tutorial.ui.edn)."
  [geom]
  (if-not (:glow-visible? geom)
    []
    (vec
     (concat
      (glow-segment-items (:glow-right-x0 geom) (:glow-right-x1 geom)
                          (:glow-y geom) (:glow-line-w geom) (:glow-sz geom))
      (glow-segment-items (:glow-left-x0 geom) (:glow-left-x1 geom)
                          (:glow-y geom) (:glow-line-w geom) (:glow-sz geom))))))

(defn- glow-geom-at
  "Staged glow growth starting at elapsed 400ms (main attach-first-open-animation!)."
  [elapsed]
  (let [s glow-s ln glow-ln ln2 glow-ln2 cl glow-cl
        dt (- (double elapsed) 400.0)
        b1 300.0 b2 200.0
        base {:glow-y (* s 15.0)
              :glow-line-w (max 1.0 (* s 5.0))
              :glow-sz (max 1.0 (* s 5.0))}]
    (cond
      (< dt 0.0)
      (assoc base :glow-visible? false
             :glow-right-x0 0.0 :glow-right-x1 0.0
             :glow-left-x0 0.0 :glow-left-x1 0.0)

      (< dt b1)
      (let [len (* ln (/ dt b1))]
        (if (> len cl)
          (assoc base :glow-visible? true
                 :glow-right-x0 (* s cl) :glow-right-x1 (* s len)
                 :glow-left-x0 (* s (- len)) :glow-left-x1 (* s (- cl)))
          (assoc base :glow-visible? false
                 :glow-right-x0 0.0 :glow-right-x1 0.0
                 :glow-left-x0 0.0 :glow-left-x1 0.0)))

      :else
      (let [ldt (min (- dt b1) b2)
            len2 (+ (- ln cl cl) (* (- ln2 (- ln cl cl)) (/ ldt b2)))]
        (assoc base :glow-visible? true
               :glow-right-x0 (* s (- ln len2)) :glow-right-x1 (* s ln)
               :glow-left-x0 (* s (- ln)) :glow-left-x1 (* s (- len2 ln)))))))

(defn- tutorial-title [lang tutorial]
  (or (when-let [id (:id tutorial)]
        (:title (tut-content/load-tutorial-content lang id)))
      (some-> tutorial :id name)
      ""))

(defn- markdown-lines
  "Project markdown segments into Presentation list/composite items.
   Text rows keep :label for :text bindings; image rows are :kind :image
   composites so the middle pane can blit textures (not `[image]` placeholders)."
  ([text misaka-id] (markdown-lines text misaka-id markdown/max-content-width))
  ([text misaka-id max-width]
   (let [w (float max-width)]
     (vec
       (mapcat (fn [segment]
                 (if (= :image (:type segment))
                   (let [h (float (or (:img-h segment) markdown/default-image-height))]
                     [{:kind :image
                       :src (:texture-path segment)
                       :x 0.0 :y 0.0 :w w :h h
                       :label ""
                       :font-size markdown/default-font-size}])
                   (map (fn [line]
                          (let [fs (or (:font-size segment) markdown/default-font-size)]
                            {:kind :text
                             :text line
                             :label line
                             :font-size fs
                             :x 0.0 :y 0.0 :w w :h markdown/line-height}))
                        (str/split-lines (str (:text segment))))))
               (markdown/render-segments (or text "") misaka-id max-width))))))

(defn- active? [player-uuid tut]
  (or (:default-installed? tut)
      (and (client-state/ready?)
           (client-state/is-activated? player-uuid (:id tut)))))

(defn- request-sync! []
  (when-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner (tut-msg/msg-id :tutorial/request-sync) {}
      (fn [response]
        (when response (client-state/apply-sync! response))))))

(defn- tutorial-items [entries player-uuid lang hovered-id selected-id]
  (let [ordered (sort-by (fn [t] (if (active? player-uuid t) 0 1)) entries)]
    (mapv (fn [t]
            (let [id (:id t)
                  hovered? (= id hovered-id)
                  selected? (= id selected-id)]
              {:label (str (when-not (active? player-uuid t) "[locked] ")
                            (tutorial-title lang t))
               :action-label "Open"
               :tutorial-id id
               ;; Cyan hover / soft-yellow selected — readable on the dark left panel.
               :rgba (cond
                       hovered? [0.208 0.780 1.0 1.0]
                       selected? [1.0 1.0 0.55 1.0]
                       :else [1.0 1.0 1.0 1.0])}))
          ordered)))

(defn- current-content [ctx]
  (let [{:keys [lang player-uuid current-tut-id]} @ctx
        ;; Open with no selection until the player picks an entry (matches main).
        cd (if current-tut-id
             (or (tut-content/load-tutorial-content lang current-tut-id) {})
             {})
        misaka (client-state/get-misaka-id player-uuid)]
    {:brief-lines (markdown-lines (:brief cd) misaka brief-width)
     :content-lines (markdown-lines (:content cd) misaka)}))

(defn- tag-tooltip-pos
  "Root-local (x,y) for the tooltip above tag `idx` — main sets the text
   node to the hovered tag's X and Y-8 (upstream font.draw at 0,-8)."
  [idx]
  (let [i (double (or idx 0))]
    {:tag-tooltip-x (float (+ tag-abs-x0 (* i tag-step)))
     :tag-tooltip-y (float (+ tag-abs-y0 tag-tooltip-y-offset))}))

(defn- current-preview [ctx]
  (let [pvs (:pvs @ctx)
        vg (preview/current-view-group pvs)
        view (preview/current-sub-view pvs)
        groups (or (:view-groups @pvs) [])
        hover-idx (:hovered-tag @ctx)
        tip (when (number? hover-idx)
              (:display-text (nth groups hover-idx nil)))]
    (merge
     {:tag-items (mapv (fn [[idx group]]
                         (let [tag (or (:tag group) :view)
                               src (or (get preview/tag-textures tag)
                                       (get preview/tag-textures :view))]
                           {:kind :image
                            :src src
                            :x 1.0 :y 1.0 :w 16.0 :h 16.0
                            :tag-index idx
                            :label (or (:display-text group) "")}))
                       (map-indexed vector groups))
      :preview-items (if view (preview/preview-items view) [])
      :tag-tooltip (or tip "")
      :button-left {:label "Previous" :visible? (> (count (or (:sub-views vg) [])) 1)}
      :button-right {:label "Next" :visible? (> (count (or (:sub-views vg) [])) 1)}}
     (tag-tooltip-pos (or hover-idx 0)))))

(defn- anim-overlay
  "Project logo/left-bg/glow animation channels into snapshot fields."
  [ctx]
  (let [{:keys [phase anim-start-ms logo-alphas logo3-y left-bg-alpha
                list-visible? panels-visible? logos-visible?
                glow-visible? glow-right-x0 glow-right-x1
                glow-left-x0 glow-left-x1 glow-y glow-line-w glow-sz]} @ctx
        alphas (or logo-alphas {})
        static (static-glow-geom)
        geom {:glow-visible? (boolean (if (nil? glow-visible?)
                                        (:glow-visible? static)
                                        glow-visible?))
              :glow-right-x0 (float (or glow-right-x0 (:glow-right-x0 static)))
              :glow-right-x1 (float (or glow-right-x1 (:glow-right-x1 static)))
              :glow-left-x0 (float (or glow-left-x0 (:glow-left-x0 static)))
              :glow-left-x1 (float (or glow-left-x1 (:glow-left-x1 static)))
              :glow-y (float (or glow-y (:glow-y static)))
              :glow-line-w (float (or glow-line-w (:glow-line-w static)))
              :glow-sz (float (or glow-sz (:glow-sz static)))}]
    (merge
     {:phase phase
      :animating? (contains? #{:first-open :fade-out} phase)
      :list-visible? (boolean list-visible?)
      :panels-visible? (boolean panels-visible?)
      ;; Keep all logo nodes mounted while the brand layer is up; fade via rgba
      ;; only. Gating visible? on (pos? alpha) hid logo0/2/3 for the whole
      ;; first-open ramp and on idle-glow (main hides them via visible?=false,
      ;; but our glow was also missing — looked like the logos never appeared).
      :logos-visible? (boolean logos-visible?)
      :logo0-visible? (boolean logos-visible?)
      :logo1-visible? (boolean logos-visible?)
      :logo2-visible? (boolean logos-visible?)
      :logo3-visible? (boolean logos-visible?)
      :left-bg-rgba (alpha->rgba (or left-bg-alpha 1.0))
      :logo0-rgba (alpha->rgba (get alphas :logo0 (if (= phase :idle-glow) 1.0 0.0)))
      :logo1-rgba (alpha->rgba (get alphas :logo1 (if (= phase :idle-glow) 1.0 0.0)))
      :logo2-rgba (alpha->rgba (get alphas :logo2 (if (= phase :idle-glow) 1.0 0.0)))
      :logo3-rgba (alpha->rgba (get alphas :logo3 (if (= phase :idle-glow) 1.0 0.0)))
      :logo3-y (float (+ logo3-layout-y
                         (- (float (or logo3-y logo3-final-y)) logo3-final-y)))
      :glow-items (glow-items geom)
      :anim-start-ms anim-start-ms}
     geom)))

(defn- snapshot [ctx]
  (let [{:keys [entries player-uuid lang current-tut-id hovered-tut]} @ctx
        items (tutorial-items entries player-uuid lang hovered-tut current-tut-id)
        selected (or (first (keep-indexed (fn [i item]
                                            (when (= (:tutorial-id item) current-tut-id) i))
                                          items))
                     -1)
        title (if current-tut-id
                (tutorial-title lang (tut-registry/tutorial-by-id current-tut-id))
                "Select a tutorial")]
    (merge {:title "MisakaCloud Terminal"
            :status title
            :tutorial-items items
            :selected (double selected)}
           (anim-overlay ctx)
           (current-content ctx)
           (current-preview ctx))))

(defn- present-ctx! [ctx vm]
  (when vm
    (presentation/present! vm (snapshot ctx))))

(defn- mark-first-open-done! []
  (when-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner (tut-msg/msg-id :tutorial/mark-first-open-done) {} nil))
  (client-state/apply-sync! {:first-open? false}))

(defn- tick-first-open! [ctx elapsed]
  (let [alphas (into {}
                     (map (fn [[id start dur]]
                            [id (logo-fade-alpha elapsed start dur)])
                          logo-timings))
        blendy-start 700.0
        blendy-dur 400.0
        ;; Main: keep logo3 at final Y until blendy starts, then jump to 63 and
        ;; slide to -36 (pre-setting 63 made it sit low for the first 0.7s).
        blendy-t (max 0.0 (min 1.0 (/ (- elapsed blendy-start) blendy-dur)))
        logo3-y (if (< elapsed blendy-start)
                  logo3-final-y
                  (+ logo3-start-y (* (- logo3-final-y logo3-start-y) blendy-t)))
        left-start 1750.0
        left-dur 300.0
        left-a (if (>= elapsed left-start)
                 (max 0.0 (min 1.0 (/ (- elapsed left-start) left-dur)))
                 0.0)
        list-visible? (>= elapsed 2400.0)
        glow (glow-geom-at elapsed)]
    (swap! ctx merge glow
           {:logo-alphas alphas
            :logo3-y logo3-y
            :left-bg-alpha left-a
            :list-visible? list-visible?
            :logos-visible? true
            :panels-visible? false})
    (when (and list-visible? (not (:first-open-marked? @ctx)))
      (mark-first-open-done!)
      ;; Keep the end-of-animation brand frame (all logos + static glow). Main's
      ;; setup-static-glow! hid 0/2/3; that read as "logos missing" once glow
      ;; was also absent on the Presentation path.
      (swap! ctx merge (static-glow-geom)
             {:first-open-marked? true
              :phase :idle-glow
              :logo-alphas {:logo0 1.0 :logo1 1.0 :logo2 1.0 :logo3 1.0}
              :logo3-y logo3-final-y
              :left-bg-alpha 1.0}))))

(defn- tick-fade-out! [ctx elapsed]
  (let [start (or (:fade-start-alphas @ctx)
                  {:logo0 1.0 :logo1 1.0 :logo2 1.0 :logo3 1.0})
        t (float (max 0.0 (min 1.0 (/ elapsed 300.0))))
        scale (float (- 1.0 t))
        alphas (into {} (map (fn [[k v]] [k (float (* (float v) scale))]) start))
        any? (some (fn [[_ v]] (pos? (float v))) alphas)]
    (swap! ctx assoc
           :logo-alphas alphas
           :logos-visible? (boolean any?)
           :glow-visible? (boolean any?))
    (when-not any?
      (swap! ctx assoc :phase :content :logos-visible? false :glow-visible? false))))

(defn- frame-tick! [ctx vm]
  (let [{:keys [phase anim-start-ms]} @ctx
        elapsed (double (- (now-ms) (or anim-start-ms (now-ms))))]
    (case phase
      :first-open (tick-first-open! ctx elapsed)
      :fade-out (tick-fade-out! ctx elapsed)
      nil)
    (when (contains? #{:first-open :fade-out} (:phase @ctx))
      (present-ctx! ctx vm))))

(defn- begin-fade-out! [ctx]
  (swap! ctx assoc :phase :fade-out :anim-start-ms (now-ms)
         :fade-start-alphas (or (:logo-alphas @ctx)
                                {:logo0 1.0 :logo1 1.0 :logo2 1.0 :logo3 1.0})
         :panels-visible? true :list-visible? true))

(defn- select-tutorial! [ctx tut-id]
  (when-let [tut (tut-registry/tutorial-by-id tut-id)]
    (let [first-selection? (nil? (:current-tut-id @ctx))]
      (when first-selection?
        (begin-fade-out! ctx))
      (swap! ctx assoc
             :current-tut-id (:id tut)
             :panels-visible? true
             :list-visible? true
             :pvs (atom (preview/create-preview-state (:id tut)))))))

(defn- dispatch! [ctx vm action current]
  (let [selected-item (:selected-item current)
        ;; Scroll/pointer noise must NOT rebuild the tutorial snapshot or call
        ;; present!: every thumb-drag event used to snapshot+present the whole
        ;; markdown/preview tree, which made the scrollbar feel stuttery.
        heavy? (not (#{:input/scroll :input/pointer :input/unknown} action))]
    (case action
      :tutorial/select
      (select-tutorial! ctx (:tutorial-id selected-item))

      :tutorial/item-hover
      (if (= :enter (:hover-event current))
        (swap! ctx assoc :hovered-tut (:tutorial-id selected-item))
        (swap! ctx assoc :hovered-tut nil))

      :tutorial/tag
      (when-let [idx (:tag-index selected-item)]
        (preview/switch-view-group! (:pvs @ctx) (int idx)))

      :tutorial/tag-hover
      (if (= :enter (:hover-event current))
        (swap! ctx assoc :hovered-tag (:tag-index selected-item))
        (swap! ctx assoc :hovered-tag nil))

      :tutorial/prev
      (preview/cycle-sub-view! (:pvs @ctx) :prev)

      :tutorial/next
      (preview/cycle-sub-view! (:pvs @ctx) :next)

      :input/scroll nil
      nil)
    (if heavy?
      (let [snap (snapshot ctx)]
        (present-ctx! ctx vm)
        snap)
      current)))

(defn- initial-phase [player-uuid]
  (if (client-state/first-open? player-uuid)
    :first-open
    :idle-glow))

(defn open! [player]
  (let [player-uuid (uuid/player-uuid player)
        _ (client-state/ensure-client-state! player-uuid)
        _ (request-sync!)
        lang (tut-content/current-lang)
        entries (vec (tut-registry/all-tutorials))
        phase (initial-phase player-uuid)
        glow0 (if (= phase :idle-glow)
                (static-glow-geom)
                {:glow-visible? false
                 :glow-right-x0 0.0 :glow-right-x1 0.0
                 :glow-left-x0 0.0 :glow-left-x1 0.0
                 :glow-y (* glow-s 15.0)
                 :glow-line-w (max 1.0 (* glow-s 5.0))
                 :glow-sz (max 1.0 (* glow-s 5.0))})
        ctx (atom (merge {:player-uuid player-uuid
                          :lang lang
                          :entries entries
                          :current-tut-id nil
                          :hovered-tut nil
                          :pvs (atom (preview/create-preview-state :welcome))
                          :phase phase
                          :anim-start-ms (now-ms)
                          :logo-alphas (if (= phase :idle-glow)
                                         {:logo0 1.0 :logo1 1.0 :logo2 1.0 :logo3 1.0}
                                         {:logo0 0.0 :logo1 0.0 :logo2 0.0 :logo3 0.0})
                          :logo3-y logo3-final-y
                          :left-bg-alpha (if (= phase :idle-glow) 1.0 0.0)
                          :list-visible? (not= phase :first-open)
                          :panels-visible? false
                          :logos-visible? true}
                         glow0))
        vm-box (atom nil)
        vm (application/mount!
             (str "application/tutorial/" player-uuid)
             "MisakaCloud Terminal"
             (snapshot ctx)
             (fn [action current] (dispatch! ctx @vm-box action current))
             (fn []
               (reset! screen-tick* nil)
               (bridge/close-screen!))
             :screen
             :academy.app/tutorial)]
    (reset! vm-box vm)
    (reset! screen-tick* (fn [] (frame-tick! ctx vm)))
    vm))

(defn screen-tick!
  "Called once per screen frame from the Presentation host refresh path."
  []
  (when-let [f @screen-tick*]
    (f)))
