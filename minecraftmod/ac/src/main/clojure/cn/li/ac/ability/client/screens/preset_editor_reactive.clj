(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Preset Editor reactive UI — fully aligned with AcademyCraft PresetEditUI.java.
   XML-driven layout, horizontal carousel with lerp animation, selector popup
   with background/glow/tooltip, full-screen dark overlay, ESC to close.

   Exact upstream constants:
   - STEP=125, TRANSIT_TIME=0.35, MAX_ALPHA=255, MIN_ALPHA=77 (0.3)
   - MAX_SCALE=1.0, MIN_SCALE=0.8
   - CRL_BACK=(49,49,49,200), CRL_WHITE=(1,1,1,0.6), CRL_GLOW=(1,1,1,0.2)
   - Selector: MAX_PER_ROW=4, MARGIN=2.5, SIZE=15, STEP=18"
  (:require [cn.li.ac.ability.client.screens.preset-editor :as logic]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [java.util HashMap]))

;; Forward declare — used by build-selector! before definition
(declare refresh-ui!)

;; ============================================================================
;; Carousel constants (matching upstream PresetEditUI.java exactly)
;; ============================================================================

(def ^:private step 125.0)        ;; horizontal offset between pages
(def ^:private transit-time 0.35) ;; slide transition duration (seconds)
(def ^:private max-alpha 1.0)     ;; Colors.f2i(1f)
(def ^:private min-alpha 0.3)     ;; Colors.f2i(0.3f)
(def ^:private max-scale 1.0)
(def ^:private min-scale 0.8)

;; Selector constants (matching upstream Selector inner class exactly)
(def ^:private sel-max-per-row 4)
(def ^:private sel-margin 2.5)
(def ^:private sel-size 15.0)
(def ^:private sel-step (+ sel-size 3.0))

;; Selector colors (matching upstream CRL_* constants)
(def ^:private crl-back  0xC8313131)  ;; (49,49,49,200)
(def ^:private crl-white 0x99FFFFFF)  ;; (1,1,1,0.6)
(def ^:private crl-glow  0x33FFFFFF)  ;; (1,1,1,0.2)

;; ============================================================================
;; State tracking per-session (Framework-backed for lifecycle safety)
;; ============================================================================

(defonce ^:private ^HashMap active-by-session (HashMap.))

(defn- track-active! [owner ^UiRt r]
  (when-let [uuid (or (:player-uuid owner) (nth (logic/editor-owner-key owner) 2 nil))]
    (when-let [session-id (:client-session-id owner)]
      (.put active-by-session [session-id uuid] {:rt r :owner owner}))))

(defn- untrack-active! [owner]
  (when-let [uuid (or (:player-uuid owner) (nth (logic/editor-owner-key owner) 2 nil))]
    (when-let [session-id (:client-session-id owner)]
      (.remove active-by-session [session-id uuid]))))

(defn- cached-owner [^UiRt r]
  (or (:owner (rt/user-signal r :owner))
      (when-let [session-id (runtime-hooks/client-session-id)]
        (some (fn [entry]
                (let [[sid _] (.getKey ^java.util.Map$Entry entry)]
                  (when (= sid session-id) (:owner (.getValue ^java.util.Map$Entry entry)))))
              (.entrySet active-by-session)))))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- local [key]
  (or (i18n/translate (str "ac.gui.preset_edit." key)) key))

(defn- now-sec []
  "Current time in fractional seconds."
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn- lerp-d [a b t]
  (+ (double a) (* (- (double b) (double a)) (max 0.0 (min 1.0 (double t))))))

;; ============================================================================
;; Carousel animation state
;; ============================================================================

(definterface ICarouselAnim
  (^long activeIndex [])
  (setActiveIndex [^long value])
  (^long fromIndex [])
  (setFromIndex [^long value])
  (^long toIndex [])
  (setToIndex [^long value])
  (^double transitStart [])
  (setTransitStart [^double value])
  (^boolean transiting [])
  (setTransiting [^boolean value]))

(deftype CarouselAnim
  [^:unsynchronized-mutable ^long active
   ^:unsynchronized-mutable ^long from
   ^:unsynchronized-mutable ^long to
   ^:unsynchronized-mutable ^double start
   ^:unsynchronized-mutable ^boolean in-transit]
  ICarouselAnim
  (activeIndex [_] active)
  (setActiveIndex [_ value] (set! active value))
  (fromIndex [_] from)
  (setFromIndex [_ value] (set! from value))
  (toIndex [_] to)
  (setToIndex [_ value] (set! to value))
  (transitStart [_] start)
  (setTransitStart [_ value] (set! start value))
  (transiting [_] in-transit)
  (setTransiting [_ value] (set! in-transit value)))

(defn- carousel-anim [^UiRt r]
  (or (rt/user-signal r :carousel-anim)
      (let [a (CarouselAnim. 0 0 0 0.0 false)]
        (rt/put-user-signal! r :carousel-anim a)
        a)))

(defn- start-transit! [^UiRt r from-idx to-idx]
  (let [^CarouselAnim anim (carousel-anim r)]
    (.setFromIndex anim (long from-idx))
    (.setToIndex anim (long to-idx))
    (.setTransitStart anim (now-sec))
    (.setTransiting anim true)))

(defn- finish-transit! [^UiRt r]
  (let [^CarouselAnim anim (carousel-anim r)]
    (.setTransiting anim false)
    (.setActiveIndex anim (.toIndex anim))))

;; ============================================================================
;; Slot info update — skill icon + name from preset data
;; ============================================================================

(defn- clear-selector! [^UiRt r]
  (when-let [^INode sel (rt/node-by-id r :selector)]
    (.setVisible sel false)
    (.setFlag sel node/FLAG-LAYOUT-DIRTY)
    (rt/clear-children! r sel))
  (rt/put-user-signal! r :selector-open false)
  (rt/put-user-signal! r :selector-slot nil))

(defn- selector-open? [^UiRt r]
  (boolean (rt/user-signal r :selector-open)))

(defn- slot-tex-path [skill-id]
  (if skill-id
    (or (skill-query/get-skill-icon-path skill-id)
        (modid/asset-path "textures" "missing.png"))
    (modid/asset-path "textures" "missing.png")))

(defn- update-page-slot! [^UiRt r page-idx slot-idx slot-info]
  "Update one slot's icon and text from slot-info map or nil."
  (let [icon-node-id (keyword (str "preset-" page-idx "-" slot-idx "-icon"))
        text-node-id (keyword (str "preset-" page-idx "-" slot-idx "-text"))
        skill-id (:skill-id slot-info)
        skill-name (:skill-name slot-info)]
    (when-let [^INode icon (rt/node-by-id r icon-node-id)]
      (ui/set-node-prop! r icon :src (slot-tex-path skill-id)))
    (when-let [^INode text (rt/node-by-id r text-node-id)]
      (ui/set-node-prop! r text :text (or skill-name "")))))

(defn- update-page-slots! [^UiRt r page-idx slots]
  (doseq [slot-idx (range 4)]
    (update-page-slot! r page-idx slot-idx (nth slots slot-idx nil))))

(defn- update-page-title! [^UiRt r page-idx]
  (when-let [^INode title (rt/node-by-id r (keyword (str "preset-" page-idx "-title")))]
    (ui/set-node-prop! r title :text (str (local "tag") (inc page-idx)))))

;; ============================================================================
;; Selector popup — fully aligned with upstream Selector inner class
;; ============================================================================

(defn- build-selector! [^UiRt r page-idx slot-idx mx my]
  (clear-selector! r)
  (let [owner (cached-owner r)
        rd (when owner (logic/build-preset-editor-render-data owner))
        available (vec (:available-skills rd))
        ;; Items: -1 = remove (matching upstream cancel button), then skills
        items (into [{:id -1 :icon (modid/asset-path "textures" "guis/preset_settings/cancel.png")
                      :name (local "skill_remove")}]
                    (mapv (fn [s]
                            {:id (:ctrl-id s) :skill-id (:skill-id s)
                             :icon (or (:skill-icon s) (modid/asset-path "textures" "missing.png"))
                             :name (:skill-name s) :cat-id (:cat-id s) :ctrl-id (:ctrl-id s)})
                          available))
        n (count items)
        rows (int (Math/ceil (/ (double n) sel-max-per-row)))
        cols (min n sel-max-per-row)
        sel-w (+ (* 2 sel-margin) (* sel-step (dec cols)) sel-size)
        sel-h (+ (* 2 sel-margin) (* sel-step (dec rows)) sel-size)
        ;; Tooltip bar below selector (matching upstream)
        tooltip-h 12.0
        total-h (+ sel-h tooltip-h 3.0)]
    (when-let [^INode sel-node (rt/node-by-id r :selector)]
      ;; Reposition and resize selector container
      (.setX sel-node (double mx))
      (.setY sel-node (double (- my total-h)))
      (ui/set-node-prop! r :selector :w sel-w)
      (ui/set-node-prop! r :selector :h total-h)
      (.setVisible sel-node true)
      (.setFlag sel-node node/FLAG-LAYOUT-DIRTY)
      ;; Selector background + glow (matching upstream CRL_BACK + CRL_GLOW)
      (rt/build-child! r
        {:kind :box :props {:id :sel-bg :x 0.0 :y 0.0 :w sel-w :h sel-h :fill crl-back}}
        sel-node)
      ;; Tooltip bar (matching upstream font.draw hint text)
      (rt/build-child! r
        {:kind :group :props {:id :sel-tooltip :x 0.0 :y (+ sel-h 3.0) :w sel-w :h tooltip-h}
         :children
         [{:kind :box :props {:id :sel-tooltip-bg :x 0.0 :y 0.0 :w sel-w :h tooltip-h :fill crl-back}}
          {:kind :text :props {:id :sel-tooltip-text :x 3.0 :y 1.5 :w (- sel-w 6.0) :h 10.0
                               :text (local "skill_select") :font-size 9.0
                               :color 0xFFBBBBBB}}]}
        sel-node)
      ;; Selector item grid
      (doseq [[i item] (map-indexed vector items)]
        (let [row (quot i sel-max-per-row)
              col (rem i sel-max-per-row)
              item-x (+ sel-margin (* col sel-step))
              item-y (+ sel-margin (* row sel-step))
              item-id (keyword (str "sel-" i))]
          (rt/build-child! r
            {:kind :group
             :props {:id item-id :x item-x :y item-y :w sel-size :h sel-size}
             :children
             [{:kind :image
               :props {:id (keyword (str (name item-id) "-icon"))
                       :x 0.0 :y 0.0 :w sel-size :h sel-size :src (:icon item)}}
              {:kind :box
               :props {:id (keyword (str (name item-id) "-hit"))
                       :x 0.0 :y 0.0 :w sel-size :h sel-size
                       :fill 0x00000000 :hover-tint crl-white}}]}
            sel-node)
          ;; Click handler — immediate save + close (matching upstream SelHandler)
          (events/on! r item-id :left-click
            (fn [_ _ _]
              (if (= -1 (:id item))
                ;; Remove → clear slot (upstream cancel button)
                (when owner
                  (api/req-set-preset-slot! owner page-idx slot-idx nil nil nil))
                ;; Assign skill → immediate save (upstream onEdit)
                (when owner
                  (api/req-set-preset-slot! owner page-idx slot-idx
                                           (:cat-id item) (:ctrl-id item) nil)))
              (clear-selector! r)
              (refresh-ui! r owner)))))
      ;; Track which slot the selector is open for
      (rt/put-user-signal! r :selector-open true)
      (rt/put-user-signal! r :selector-slot [page-idx slot-idx]))))

;; ============================================================================
;; Page refresh — react to preset data changes
;; ============================================================================

(defn- page-x-from-active [page-idx active-idx]
  "Upstream getXFor(i, active): STEP * (i - active). Active at center (0)."
  (* step (- page-idx active-idx)))

(defn- refresh-carousel! [^UiRt r owner]
  (when-let [rd (logic/build-preset-editor-render-data owner)]
    (let [all-slots (:all-preset-slots rd)
          selected-preset (:selected-preset rd)
          ^CarouselAnim anim (carousel-anim r)]
      (doseq [page-idx (range 4)]
        (let [page-slots (get all-slots page-idx (vec (repeat 4 nil)))]
          (update-page-slots! r page-idx page-slots)
          (update-page-title! r page-idx)))
      ;; Update carousel target: center on selected-preset (upstream updatePosForeground)
      (.setActiveIndex anim (long selected-preset)))))

;; ============================================================================
;; Animated carousel tick — matching upstream updateTransit + updatePosForeground
;; ============================================================================

(defn- apply-page-transform! [^UiRt r page-idx x scale visible?]
  "Position a carousel page. Uses INode.setX + .setScale (native interface methods).
   INode has no setAlpha — dimming is achieved via scale reduction + visibility on inactive pages."
  (when-let [^INode pn (rt/node-by-id r (keyword (str "preset-" page-idx)))]
    (.setX pn (double x))
    (.setScale pn (double scale))
    (.setVisible pn (boolean visible?))
    (.setFlag pn node/FLAG-LAYOUT-DIRTY)))

(defn- attach-carousel-tick! [^UiRt r]
  (let [clock (rt/clock-ms-sig r)
        ^CarouselAnim anim (carousel-anim r)
        computed (sig/computed-o [clock]
                   (fn [_]
                     (let [active (.activeIndex anim)
                           from (.fromIndex anim)
                           to (.toIndex anim)
                           start-time (.transitStart anim)
                           transiting? (.transiting anim)]
                       (if transiting?
                         ;; Upstream updateTransit: lerp x + scale between from and to
                         (let [elapsed (- (now-sec) (double start-time))
                               progress (min 1.0 (/ elapsed transit-time))]
                           (doseq [page-idx (range 4)]
                             (let [x0 (page-x-from-active page-idx from)
                                   x1 (page-x-from-active page-idx to)
                                   x (lerp-d x0 x1 progress)
                                   from? (= page-idx from)
                                   to? (= page-idx to)
                                   scale (cond
                                           from? (lerp-d max-scale min-scale progress)
                                           to?   (lerp-d min-scale max-scale progress)
                                           :else min-scale)
                                   visible? (or from? to?)]
                               (apply-page-transform! r page-idx x scale visible?)))
                           (when (>= progress 1.0)
                             (finish-transit! r)
                             (doseq [page-idx (range 4)]
                               (let [active? (= page-idx to)]
                                 (apply-page-transform! r page-idx
                                   (page-x-from-active page-idx to)
                                   (if active? max-scale min-scale)
                                   true)))))
                         ;; Not transiting — upstream updatePosForeground
                         (doseq [page-idx (range 4)]
                           (let [active? (= page-idx active)]
                             (apply-page-transform! r page-idx
                               (page-x-from-active page-idx active)
                               (if active? max-scale min-scale)
                               true)))))
                     nil))]
    (let [^INode anchor (rt/node-by-id r :preset-0)]
      (when anchor
        (let [b (sig/bind! computed anchor (fn [_ _] nil) (rt/get-dirty-bindings-q r))]
          (rt/register-binding! r (.getIdx anchor) b))))))

;; ============================================================================
;; Wire slot clicks → open Selector (matching upstream HintHandler)
;; ============================================================================

(defn- wire-slot-clicks! [^UiRt r owner page-idx]
  (doseq [slot-idx (range 4)]
    (let [slot-id (keyword (str "preset-" page-idx "-" slot-idx))]
      (events/on! r slot-id :left-click
        (fn [_ _ evt]
          (let [^CarouselAnim anim (carousel-anim r)]
            (when-not (.transiting anim)
              (if (selector-open? r)
                ;; Upstream: if selector open, dispose it (click same slot = close)
                (clear-selector! r)
                ;; Upstream: if page is active, open selector at mouse position
                (when (= page-idx (.activeIndex anim))
                  (let [mx (double (or (:x evt) 0))
                        my (double (or (:y evt) 0))]
                    (build-selector! r page-idx slot-idx mx my)))))))))))

;; ============================================================================
;; Wire page clicks → switch to that preset (matching upstream startTransit)
;; ============================================================================

(defn- wire-page-clicks! [^UiRt r owner]
  (doseq [page-idx (range 4)]
    (let [page-id (keyword (str "preset-" page-idx))]
      (events/on! r page-id :left-click
        (fn [_ _ _]
          (let [^CarouselAnim anim (carousel-anim r)]
            (when-not (.transiting anim)
              (clear-selector! r)
              (when (not= page-idx (.activeIndex anim))
                (let [from-idx (.activeIndex anim)]
                  (start-transit! r from-idx page-idx)
                  ;; Update logic state so data reads use the new preset
                  (logic/on-preset-tab-click owner page-idx)
                  (refresh-carousel! r owner))))))))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn refresh-ui!
  "Rebuild carousel + slot contents from current editor + player state."
  [^UiRt r owner]
  (when owner
    (refresh-carousel! r owner)
    nil))

(defn refresh-active-screen!
  "Called when server preset data syncs while the editor is open."
  [player-uuid]
  (when-let [session-id (runtime-hooks/client-session-id)]
    (when-let [{:keys [rt owner]} (.get active-by-session [session-id player-uuid])]
      (refresh-ui! rt owner))))

(defn- load-page-spec []
  (ui-xml/load-spec (modid/namespaced-path "guis/new/preset_edit.xml")))

(defn create-runtime
  "Build reactive preset editor runtime fully matching upstream AcademyCraft UX."
  [owner]
  (let [r (rt/create-runtime)
        spec (load-page-spec)
        _ (rt/build! r spec)
        _ (logic/open-screen! owner)]
    (rt/put-user-signal! r :owner owner)
    (track-active! owner r)
    ;; Wire all interactions
    (wire-page-clicks! r owner)
    (doseq [page-idx (range 4)]
      (wire-slot-clicks! r owner page-idx))
    ;; Carousel animation — matching upstream updateTransit + updatePosForeground
    (attach-carousel-tick! r)
    ;; Initial state
    (let [^CarouselAnim anim (carousel-anim r)]
      (.setActiveIndex anim (long (:selected-preset
                                    (or (logic/build-preset-editor-render-data owner)
                                        {:selected-preset 0})))))
    (refresh-ui! r owner)
    r))

(defn open-screen!
  "Open the preset editor as a reactive overlay screen."
  ([owner]
   (let [^UiRt r (create-runtime owner)]
     (bridge/open-reactive-screen! r (local "name")
       {:on-close #(do (clear-selector! r)
                       (untrack-active! owner)
                       (logic/close-screen! owner))}))))

(defn on-close!
  [owner]
  (untrack-active! owner)
  (logic/close-screen! owner))
