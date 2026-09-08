(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Presentation Runtime controller for the AC preset editor.
   Carousel + Selector match main PresetEditUI:
   STEP=125, TRANSIT_TIME=0.35, scale 1.0/0.8, alpha 1.0/0.3;
   Selector MAX_PER_ROW=4, MARGIN=2.5, SIZE=15, STEP=18 at pointer."
  (:require [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.screens.preset-editor :as editor]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.ac.config.modid :as modid]))

(defonce ^:private active-mounts (atom {}))
(defonce ^:private screen-tick* (atom nil))

;; ---------------------------------------------------------------------------
;; Carousel constants (main PresetEditUI.java)
;; ---------------------------------------------------------------------------

(def ^:private step 125.0)
(def ^:private transit-time 0.35)
(def ^:private max-alpha 1.0)
(def ^:private min-alpha 0.3)
(def ^:private max-scale 1.0)
(def ^:private min-scale 0.8)

;; Main preset_edit.xml page / slot metrics (scale 1.0)
(def ^:private page-w 116.2)
(def ^:private page-h 141.5)
(def ^:private title-w 80.0)
(def ^:private title-h 15.0)
(def ^:private title-y -15.0)
(def ^:private slot-w 110.0)
(def ^:private slot-h 34.8)
(def ^:private slot-ys [1.5 36.2 71.0 105.8])
(def ^:private icon-s 26.5)
(def ^:private icon-x 2.2)
(def ^:private icon-y 3.8)
(def ^:private text-x 36.2)
(def ^:private text-y 10.0)
(def ^:private text-w 62.5)
(def ^:private text-h 15.0)
;; Design 540×280 — page center at mid of full stage (title is absolute overlay)
(def ^:private design-w 540.0)
(def ^:private design-h 280.0)
(def ^:private carousel-center-x (/ design-w 2.0))
(def ^:private carousel-h design-h)

;; Selector constants (main Selector inner class)
(def ^:private sel-max-per-row 4)
(def ^:private sel-margin 2.5)
(def ^:private sel-size 15.0)
(def ^:private sel-step (+ sel-size 3.0))
(def ^:private cancel-tex
  (str "academy:textures/guis/preset_settings/cancel.png"))

(def ^:private missing-tex
  (modid/asset-path "textures" "missing.png"))

(defn- skill-icon-or-missing
  "Match main PresetEditUI: empty skill :icon still shows a placeholder so
   learned controllable skills stay visible in the selector / slot rows."
  [skill-id preferred]
  (let [src (or (when (seq (str preferred)) (str preferred))
                (when skill-id (skill-query/get-skill-icon-path skill-id)))]
    (if (seq (str src)) src missing-tex)))

(defn- now-sec []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn- lerp-d [a b t]
  (+ (double a) (* (- (double b) (double a)) (max 0.0 (min 1.0 (double t))))))

(defn- page-x-from-active
  "Upstream getXFor(i, active): STEP * (i - active). Active at center (0)."
  [page-idx active-idx]
  (* step (- (double page-idx) (double active-idx))))

(defn- fresh-anim [active]
  {:active (long active)
   :from (long active)
   :to (long active)
   :start 0.0
   :transiting? false})

(defn- start-transit! [anim* to-idx]
  (let [a @anim*
        from (long (:active a))
        to (long to-idx)]
    (when (and (not (:transiting? a)) (not= from to))
      (reset! anim* {:active from
                     :from from
                     :to to
                     :start (now-sec)
                     :transiting? true})
      true)))

(defn- finish-transit! [anim*]
  (let [to (long (:to @anim*))]
    (reset! anim* (fresh-anim to))))

(defn- transit-progress [anim]
  (if-not (:transiting? anim)
    1.0
    (min 1.0 (/ (- (now-sec) (double (:start anim))) transit-time))))

(defn- page-visual
  "Per-page x/scale/alpha for current anim frame (main updateTransit)."
  [page-idx anim]
  (let [idx (long page-idx)]
    (if-not (:transiting? anim)
      (let [active? (= idx (long (:active anim)))]
        {:x (page-x-from-active idx (:active anim))
         :scale (if active? max-scale min-scale)
         :alpha (if active? max-alpha min-alpha)})
      (let [progress (transit-progress anim)
            from (long (:from anim))
            to (long (:to anim))
            x0 (page-x-from-active idx from)
            x1 (page-x-from-active idx to)
            from? (= idx from)
            to? (= idx to)]
        {:x (lerp-d x0 x1 progress)
         :scale (cond
                  from? (lerp-d max-scale min-scale progress)
                  to? (lerp-d min-scale max-scale progress)
                  :else min-scale)
         :alpha (cond
                  from? (lerp-d max-alpha min-alpha progress)
                  to? (lerp-d min-alpha max-alpha progress)
                  :else min-alpha)}))))

(defn- layout-for-visual
  "Map page-x/scale into absolute page bounds (main setScale → baked geometry)."
  [{:keys [x scale alpha]}]
  (let [s (double scale)
        w (* page-w s)
        h (* page-h s)
        a (double alpha)
        tw (* title-w s)
        th (* title-h s)]
    {:x (- (+ carousel-center-x (double x)) (/ w 2.0))
     :y (/ (- carousel-h h) 2.0)
     :w w
     :h h
     :title-x (/ (- w tw) 2.0)
     :title-y (* title-y s)
     :title-w tw
     :title-h th
     :font-size (* 10.0 s)
     :tint [1.0 1.0 1.0 a]
     :alpha a
     :scale s}))

;; ---------------------------------------------------------------------------
;; Render data
;; ---------------------------------------------------------------------------

(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "preset-editor"))

(defn- local-key [suffix]
  (let [k (str "gui." modid/MOD-ID ".preset_edit." suffix)
        t (i18n/translate k)]
    (if (= t k) suffix t)))

(defn- local-title []
  (let [t (local-key "name")]
    (if (= t "name") "Preset Edit" t)))

(defn- local-skill-hint []
  (let [t (local-key "skill_select")]
    (if (= t "skill_select") "Select skill" t)))

(defn- icon-rgba [^double alpha]
  (let [a (long (* 255.0 (max 0.0 (min 1.0 alpha))))]
    (unchecked-int (bit-or (bit-shift-left a 24) 0x00FFFFFF))))

(defn- icon-item
  "Composite IR for a skill icon (dynamic resource via PaintKernel)."
  [src w h alpha]
  (when (seq (str src))
    {:kind :image :src (str src) :x 0.0 :y 0.0
     :w (double w) :h (double h)
     :rgba (icon-rgba (double (or alpha 1.0)))}))

(defn- slot-row [preset-index slot-index slot selected-slot tint alpha scale]
  (let [s (double scale)
        skill-id (:skill-id slot)
        icon-sz (* icon-s s)
        icon-src (when skill-id
                   (skill-icon-or-missing skill-id (:skill-icon slot)))
        icon (icon-item icon-src icon-sz icon-sz alpha)
        selected? (= selected-slot [preset-index slot-index])
        sw (* slot-w s)
        sh (* slot-h s)
        skill-name (or (:skill-name slot)
                       (some-> skill-id name)
                       "")]
    {:kind :slot
     :preset-index preset-index
     :slot-index slot-index
     :index preset-index
     :skill-id skill-id
     :skill-name skill-name
     ;; Nested icon map for composite :item [:item :slot-icon] bind.
     :slot-icon icon
     :has-icon? (boolean icon)
     :icon-items (if icon [icon] [])
     :selected? selected?
     :tint tint
     :sx (/ (- (* page-w s) sw) 2.0)
     :sy (* (double (nth slot-ys slot-index)) s)
     :sw sw
     :sh sh
     :icon-x (* icon-x s)
     :icon-y (* icon-y s)
     :icon-s icon-sz
     :text-x (* text-x s)
     :text-y (* text-y s)
     :text-w (* text-w s)
     :text-h (* text-h s)
     :font-size (* 10.0 s)}))

(defn- card-entry [preset-index slots selected-preset selected-slot anim]
  (let [visual (layout-for-visual (page-visual preset-index anim))
        tint (:tint visual)
        alpha (:alpha visual)
        s (double (:scale visual))]
    (merge
     {:kind :card
      :index preset-index
      :title (str "Preset #" (inc preset-index))
      :active? (= preset-index selected-preset)
      :slots (mapv (fn [idx]
                     (slot-row preset-index idx (nth slots idx nil)
                               selected-slot tint alpha s))
                   (range 4))}
     (select-keys visual [:x :y :w :h :tint :font-size
                          :title-x :title-y :title-w :title-h]))))

(defn- clamp-selector-pos [x y w h]
  (let [pad 2.0
        x (max pad (min (- design-w w pad) (double x)))
        ;; Leave room for tooltip above (13.5)
        y (max 16.0 (min (- design-h h pad) (double y)))]
    [x y]))

(defn- fit-content-origin
  "Match presentation-core content-rect for full-screen :fit hosts."
  []
  (let [sz (bridge/get-window-size)
        sw (double (cond (vector? sz) (nth sz 0 design-w)
                         (map? sz) (or (:width sz) design-w)
                         :else design-w))
        sh (double (cond (vector? sz) (nth sz 1 design-h)
                         (map? sz) (or (:height sz) design-h)
                         :else design-h))]
    [(float (quot (- (int sw) (int design-w)) 2))
     (float (quot (- (int sh) (int design-h)) 2))]))

(defn- design-xy-from-payload
  "Activate payload x/y are layout-absolute (same space as HitKernel).
   Selector state is design-local under the centered 540×280 root."
  [payload]
  (let [[ox oy] (fit-content-origin)
        px (double (or (:x payload) (+ ox (/ design-w 2.0))))
        py (double (or (:y payload) (+ oy (/ design-h 2.0))))]
    [(- px ox) (- py oy)]))

(defn- tip-width-for [text sel-w]
  (let [raw (+ 6.0 (double (or (bridge/font-width-optional (str text))
                               (* 5.0 (count (str text))))))]
    (max 20.0 (min (double sel-w) raw))))

(defn- selector-empty-hint
  "Visible in-game proof when the picker has only cancel (or nothing)."
  [debug]
  (let [learned (long (or (:learned-count debug) -1))
        resolved (long (or (:resolved-count debug) -1))
        bindable (long (or (:bindable-count debug) -1))
        assigned (long (or (:assigned-count debug) -1))
        avail (long (or (:available-count debug) 0))
        cat (or (:category-id debug) "?")]
    (str "empty L=" learned " R=" resolved " B=" bindable
         " As=" assigned " A=" avail " cat=" cat)))

(defn- selector-grid
  "Main Selector: cancel + learned skills as a 15×15 icon grid at mouse (mx, my).
   Names live in the hover tip (and :label), not as cell text — matching
   PresetEditUI / main `build-selector!` (SIZE=15, STEP=18, MAX_PER_ROW=4)."
  ([skills mx my] (selector-grid skills mx my nil))
  ([skills mx my debug]
   (let [items (into [{:remove? true
                       :label (local-key "cancel")
                       :src cancel-tex}]
                     (map (fn [skill]
                            {:remove? false
                             :skill-id (:skill-id skill)
                             :cat-id (:cat-id skill)
                             :ctrl-id (:ctrl-id skill)
                             :label (str (or (:skill-name skill) "?"))
                             :src (skill-icon-or-missing (:skill-id skill)
                                                         (:skill-icon skill))})
                          skills))
         n (count items)
         rows (int (Math/ceil (/ (double (max 1 n)) sel-max-per-row)))
         cols (min (max 1 n) sel-max-per-row)
         sel-w (+ (* 2.0 sel-margin) (* sel-step (double (dec cols))) sel-size)
         sel-h (+ (* 2.0 sel-margin) (* sel-step (double (dec rows))) sel-size)
         [sx sy] (clamp-selector-pos mx my sel-w sel-h)
         hint (if (seq skills)
                (local-skill-hint)
                (selector-empty-hint debug))
         hint-w (tip-width-for hint (max sel-w 120.0))
         placed (mapv (fn [i item]
                        (let [row (quot i sel-max-per-row)
                              col (rem i sel-max-per-row)
                              cx (+ sel-margin (* col sel-step))
                              cy (+ sel-margin (* row sel-step))]
                          (assoc item
                                 :kind :image
                                 :x 0.0 :y 0.0
                                 :w sel-size :h sel-size
                                 :rgba (icon-rgba 1.0)
                                 :cell-x cx :cell-y cy :index i)))
                      (range n) items)]
     {:selector-visible? true
      :selector-x (double sx)
      :selector-y (double sy)
      :selector-w (double sel-w)
      :selector-h (double sel-h)
      :selector-tip-x (double sx)
      :selector-tip-y (double (- sy 13.5))
      :selector-hint hint
      :selector-hint-w (double hint-w)
      :selector-skills placed})))

(defn- with-selector-hint
  "Update tip text/width for the currently open selector (hover)."
  [selector hint]
  (let [hint (str (or hint (local-skill-hint)))
        sel-w (double (or (:selector-w selector) sel-size))]
    (assoc selector
           :selector-hint hint
           :selector-hint-w (tip-width-for hint sel-w))))

(defn- as-kw
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn- assign-controllable
  "Resolve [cat-id ctrl-id] from a selector item (or skill-id fallback)."
  [item]
  (let [cat (as-kw (:cat-id item))
        ctrl (as-kw (:ctrl-id item))]
    (cond
      (and cat ctrl) [cat ctrl]
      (:skill-id item)
      (skill-query/controllable-key (as-kw (:skill-id item)))
      :else nil)))

(defn- closed-selector []
  {:selector-visible? false
   :selector-x 0.0
   :selector-y 0.0
   :selector-w 0.0
   :selector-h 0.0
   :selector-tip-x 0.0
   :selector-tip-y 0.0
   :selector-hint ""
   :selector-hint-w 0.0
   :selector-skills []})

(defn- render-state
  ([owner] (render-state owner nil (fresh-anim 0) nil))
  ([owner selected-slot] (render-state owner selected-slot (fresh-anim 0) nil))
  ([owner selected-slot anim] (render-state owner selected-slot anim nil))
  ([owner selected-slot anim selector]
   (let [data (or (editor/build-preset-editor-render-data owner) {})
         selected-preset (int (or (:selected-preset data) 0))
         all-slots (or (:all-preset-slots data) {})
         anim-map (or anim (fresh-anim selected-preset))
         cards (mapv (fn [idx]
                       (card-entry idx
                                   (vec (or (get all-slots idx)
                                            (when (= idx selected-preset)
                                              (:slots data))
                                            []))
                                   selected-preset
                                   selected-slot
                                   anim-map))
                     (range 4))
         base {:title (local-title)
               :cards cards}]
     (merge base (or selector (closed-selector))))))

(defn- patch-local-preset-slot!
  "Optimistic client preset write so the carousel updates before sync arrives."
  [owner preset-idx key-idx controllable]
  (let [owner-key (editor/editor-owner-key owner)]
    (read-model/with-player-state-owner owner-key
      (fn [session-id player-uuid]
        (let [ps (or (store/get-player-state session-id player-uuid) {})
              pd (or (:preset-data ps) (preset-data/new-preset-data))
              new-pd (preset-data/set-slot pd preset-idx key-idx controllable)]
          (command-rt/run-command-in-session!
           session-id player-uuid
           {:command :hydrate-player-state :preset-data new-pd}
           {:mark-dirty? false}))))))

(defn refresh-ui!
  "Re-present the open preset editor for this player uuid (or mount entry).
   Rebuilds an open skill selector from fresh ability-data (learn_all sync)."
  [player-uuid-or-mount]
  (when-let [{:keys [present! owner selected-slot* selector* anim*]}
             (or (get @active-mounts (str player-uuid-or-mount))
                 (get @active-mounts player-uuid-or-mount))]
    (when (and owner selector* selected-slot* (:selector-visible? @selector*))
      (let [[_p _s] @selected-slot*
            data (or (editor/build-preset-editor-render-data owner) {})
            skills (vec (or (:available-skills data) []))
            sx (double (or (:selector-x @selector*) 0.0))
            sy (double (or (:selector-y @selector*) 0.0))]
        (reset! selector* (selector-grid skills sx sy (:debug data)))))
    (when present! (present!))))

(defn refresh-active-screen! [player-uuid]
  ;; active-mounts is keyed by player-uuid string (see open!). Looking up by
  ;; mount token silently no-oped after preset sync — reopen was required.
  (refresh-ui! player-uuid))

(defn create-runtime [owner]
  {:owner owner :state (atom (render-state owner nil))})

(defn- advance-and-present!
  "Per-frame carousel tick while the preset editor screen is open."
  [anim* selected-slot* selector* present!]
  (when (:transiting? @anim*)
    (when (>= (transit-progress @anim*) 1.0)
      (finish-transit! anim*))
    (present!)))

(defn screen-tick!
  "Called once per screen frame from the Presentation host refresh path."
  []
  (when-let [f @screen-tick*]
    (f)))

(defn open! [player-uuid]
  (let [owner (owner-for player-uuid)
        selected-slot* (atom nil)
        selector* (atom (closed-selector))
        mount* (atom nil)
        data0 (or (editor/build-preset-editor-render-data owner) {})
        anim* (atom (fresh-anim (int (or (:selected-preset data0) 0))))
        on-close (fn []
                   (reset! screen-tick* nil)
                   (swap! active-mounts dissoc (str player-uuid))
                   (editor/close-screen! owner))
        present! (fn []
                   (when-let [vm @mount*]
                     (presentation/present! vm
                       (render-state owner @selected-slot* @anim* @selector*))))]
    (editor/open-screen! owner)
    (let [vm (presentation/mount-view!
               {:view-id :academy.app/preset-editor
                :host-kind :screen
                :state (render-state owner @selected-slot* @anim* @selector*)
                :dispatch-action!
                (fn [action payload _current]
                  (let [item (:item payload)
                        anim @anim*
                        close-sel! (fn []
                                     (reset! selector* (closed-selector))
                                     (reset! selected-slot* nil))]
                    (case action
                      :preset/select-tab
                      (let [idx (int (:index item 0))]
                        (when-not (:transiting? anim)
                          (close-sel!)
                          (if (= idx (long (:active anim)))
                            (render-state owner nil @anim* @selector*)
                            (do (start-transit! anim* idx)
                                (editor/on-preset-tab-click owner idx)
                                (render-state owner nil @anim* @selector*)))))

                      :preset/select-slot
                      (let [p (int (:preset-index item
                                                  (:index item 0)))
                            s (int (:slot-index item 0))
                            ;; Main HintHandler: selector top-left at mouse.
                            [mx my] (design-xy-from-payload payload)]
                        (when-not (:transiting? anim)
                          (if (not= p (long (:active anim)))
                            (do (close-sel!)
                                (start-transit! anim* p)
                                (editor/on-preset-tab-click owner p)
                                (render-state owner nil @anim* @selector*))
                            ;; Active page: toggle selector at pointer
                            (if (and @selected-slot*
                                     (= @selected-slot* [p s])
                                     (:selector-visible? @selector*))
                              (do (close-sel!)
                                  (render-state owner nil @anim* @selector*))
                              (let [data (or (editor/build-preset-editor-render-data owner) {})
                                    skills (vec (or (:available-skills data) []))
                                    grid (selector-grid skills mx my (:debug data))]
                                (editor/on-preset-tab-click owner p)
                                (reset! selected-slot* [p s])
                                (reset! selector* grid)
                                (render-state owner @selected-slot* @anim* grid))))))

                      :preset/skill-hover
                      (when (:selector-visible? @selector*)
                        (let [hint (if (:hover? payload)
                                     (or (:label item)
                                         (some-> (:skill-id item) skill-query/skill-display-name)
                                         (local-skill-hint))
                                     (local-skill-hint))
                              next (with-selector-hint @selector* hint)]
                          (reset! selector* next)
                          (render-state owner @selected-slot* @anim* next)))

                      :preset/selector-bg
                      ;; Absorb clicks on grid chrome; keep selector open.
                      (render-state owner @selected-slot* @anim* @selector*)

                      :preset/selector-dismiss
                      ;; Full-screen blocker behind the popup: outside click closes
                      ;; without activating the carousel slot underneath.
                      (do (close-sel!)
                          (render-state owner nil @anim* @selector*))

                      :preset/assign
                      (when-let [[p s] @selected-slot*]
                        (cond
                          (:remove? item)
                          (do (patch-local-preset-slot! owner p s nil)
                              (api/req-set-preset-slot! owner p s nil nil nil)
                              (close-sel!)
                              (render-state owner nil @anim* @selector*))

                          :else
                          (if-let [[cat ctrl] (assign-controllable item)]
                            (do (patch-local-preset-slot! owner p s [cat ctrl])
                                (api/req-set-preset-slot! owner p s cat ctrl nil)
                                (close-sel!)
                                (render-state owner nil @anim* @selector*))
                            (render-state owner @selected-slot* @anim* @selector*))))

                      :preset/previous
                      (let [selected (long (:active @anim*))
                            idx (mod (dec selected) 4)]
                        (close-sel!)
                        (when (start-transit! anim* idx)
                          (editor/on-preset-tab-click owner idx))
                        (render-state owner nil @anim* @selector*))

                      :preset/next
                      (let [selected (long (:active @anim*))
                            idx (mod (inc selected) 4)]
                        (close-sel!)
                        (when (start-transit! anim* idx)
                          (editor/on-preset-tab-click owner idx))
                        (render-state owner nil @anim* @selector*))

                      (render-state owner @selected-slot* @anim* @selector*))))
                :on-close on-close})]
      (reset! mount* vm)
      (reset! screen-tick*
              (fn [] (advance-and-present! anim* selected-slot* selector* present!)))
      (swap! active-mounts assoc (str player-uuid)
             {:mount (:mount vm) :owner owner :selected-slot* selected-slot*
              :selector* selector* :anim* anim* :present! present!})
      (bridge/call-adapter :presentation-open-screen!
                           (:mount vm) "Preset Editor" on-close)
      vm)))

(defn open-screen! [owner]
  (open! (nth (editor/editor-owner-key owner) 2)))

(defn on-close! [owner]
  (reset! screen-tick* nil)
  (editor/close-screen! owner)
  nil)
