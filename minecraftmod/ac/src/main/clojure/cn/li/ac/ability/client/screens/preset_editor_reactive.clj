(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Presentation Runtime controller for the AC preset editor.
   Carousel + Selector match main PresetEditUI:
   STEP=125, TRANSIT_TIME=0.35, scale 1.0/0.8, alpha 1.0/0.3;
   Selector MAX_PER_ROW=4, MARGIN=2.5, SIZE=15, STEP=18 at pointer."
  (:require [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.screens.preset-editor :as editor]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
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
        icon-src (when skill-id (skill-query/get-skill-icon-path skill-id))
        icon (icon-item icon-src icon-sz icon-sz alpha)
        selected? (= selected-slot [preset-index slot-index])
        sw (* slot-w s)
        sh (* slot-h s)]
    {:kind :slot
     :preset-index preset-index
     :slot-index slot-index
     :index preset-index
     :skill-id skill-id
     :skill-name (or (:skill-name slot) "")
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

(defn- selector-grid
  "Main Selector: cancel + learned skills as a compact icon grid at (mx, my).
   Each grid cell is a CompositeSpec map (paint + hit share the same 15×15 box)."
  [skills mx my]
  (let [raw (into [{:remove? true
                    :label (local-key "cancel")
                    :src cancel-tex}]
                  (map (fn [skill]
                         (let [icon-src (or (:skill-icon skill)
                                            (when-let [sid (:skill-id skill)]
                                              (skill-query/get-skill-icon-path sid)))]
                           {:remove? false
                            :skill-id (:skill-id skill)
                            :cat-id (:cat-id skill)
                            :ctrl-id (:ctrl-id skill)
                            :label (str (or (:skill-name skill) "?"))
                            :src (str (or icon-src ""))}))
                       skills))
        ;; Drop entries with no drawable src except cancel (always has tex)
        items (vec (filter (fn [it] (or (:remove? it) (seq (:src it)))) raw))
        n (count items)
        rows (int (Math/ceil (/ (double (max 1 n)) sel-max-per-row)))
        cols (min (max 1 n) sel-max-per-row)
        sel-w (+ (* 2.0 sel-margin) (* sel-step (double (dec cols))) sel-size)
        sel-h (+ (* 2.0 sel-margin) (* sel-step (double (dec rows))) sel-size)
        [sx sy] (clamp-selector-pos mx my sel-w sel-h)
        hint (local-skill-hint)
        ;; Cap tip to grid width so a long i18n string cannot look like a wide panel.
        raw-hint-w (+ 6.0 (double (or (bridge/font-width-optional (str hint))
                                      (* 5.0 (count (str hint))))))
        hint-w (max 20.0 (min (double sel-w) raw-hint-w))
        placed (mapv (fn [i item]
                       (let [row (quot i sel-max-per-row)
                             col (rem i sel-max-per-row)
                             cx (+ sel-margin (* col sel-step))
                             cy (+ sel-margin (* row sel-step))]
                         (merge item
                                {:kind :image
                                 :x 0.0 :y 0.0
                                 :w sel-size :h sel-size
                                 :rgba (icon-rgba 1.0)
                                 :cell-x cx
                                 :cell-y cy
                                 :index i})))
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
     :selector-skills placed}))

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

(defn refresh-ui! [mount owner]
  (when-let [{:keys [present!]} (get @active-mounts mount)]
    (present!)))

(defn refresh-active-screen! [player-uuid]
  (when-let [{:keys [mount owner]} (get @active-mounts (str player-uuid))]
    (refresh-ui! mount owner)))

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
                        refresh (fn [] (present!) nil)
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
                            mx (double (or (:x payload) (/ design-w 2.0)))
                            my (double (or (:y payload) (/ design-h 2.0)))]
                        (when-not (:transiting? anim)
                          (if (not= p (long (:active anim)))
                            (do (close-sel!)
                                (start-transit! anim* p)
                                (editor/on-preset-tab-click owner p)
                                (render-state owner nil @anim* @selector*))
                            ;; Active page: toggle selector at pointer (main HintHandler)
                            (if (and @selected-slot*
                                     (= @selected-slot* [p s])
                                     (:selector-visible? @selector*))
                              (do (close-sel!)
                                  (render-state owner nil @anim* @selector*))
                              (let [data (or (editor/build-preset-editor-render-data owner) {})
                                    grid (selector-grid (:available-skills data) mx my)]
                                (editor/on-preset-tab-click owner p)
                                (reset! selected-slot* [p s])
                                (reset! selector* grid)
                                (render-state owner @selected-slot* @anim* grid))))))

                      :preset/assign
                      (when-let [[p s] @selected-slot*]
                        (cond
                          (:remove? item)
                          (api/req-set-preset-slot! owner p s nil nil
                                                    (fn [_]
                                                      (close-sel!)
                                                      (refresh)))

                          (:ctrl-id item)
                          (api/req-set-preset-slot! owner p s
                                                    (:cat-id item) (:ctrl-id item)
                                                    (fn [_]
                                                      (close-sel!)
                                                      (refresh))))
                        (render-state owner @selected-slot* @anim* @selector*))

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
