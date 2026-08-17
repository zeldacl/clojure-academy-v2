(ns cn.li.ac.ability.client.presentation-hud
  "Combat HUD vertical slice for Presentation Runtime.

   The existing HUD builders remain the source of game projection data. This
   namespace is the AC ViewModel/Action controller boundary; it does not emit
   Minecraft draw calls or own hover/focus/animation state.

   reactive-hud/build-snapshot's fields land here as flat bindings. Fields
   shaped as heterogeneous overlays (toasts, coin-qte, movement hints, ...)
   are flattened into :composite-list items — {:kind :quad|:image|:text ...}
   maps that presentation-compiler's render.clj already knows how to turn
   into Quad/Image/GlyphRun commands generically, so this namespace stays
   the only place that understands what any of these fields mean."
  (:require [cn.li.ac.ability.client.reactive-hud :as reactive-hud]
            [cn.li.presentation.core.host :as bridge])
  (:import [cn.li.presentation.core ActionId ActionPayload ActionResult BindingTable
            PresentationViewModel]))

(def binding-ids
  {:cp-ratio 0
   :overload-ratio 1
   :skills 2
   :selected-skill 3
   :cooldowns 4
   :crosshair 5
   :screen-flash-alpha 6
   :background-mask 7
   :cp-full-glow 8
   :movement-hints 9
   :activation-indicator 10
   :preset-indicators 11
   :numbers-texts 12
   :vm-waves 13
   :charging 14
   :charging-arcs 15
   :coin-qte 16
   :toasts 17
   :tutorial-notification 18
   :debug-lines 19
   :overload-pulse 20
   :interfered 21})

(def action-ids
  {0 :combat/select-skill
   1 :combat/toggle-skill-wheel})

;; ---------------------------------------------------------------------------
;; Color packing — reactive-hud/hud.clj builders use two conventions
;; (0.0-1.0 doubles for background-mask, 0-255 ints/vectors everywhere else).
;; render.clj's composite-list expects a single packed ARGB int per item, so
;; the conversion lives here rather than teaching the neutral renderer both
;; game-specific conventions.
;; ---------------------------------------------------------------------------

(defn- argb01
  "Pack a {:r :g :b :a} map of 0.0-1.0 doubles into an ARGB int."
  [{:keys [r g b a] :or {r 0.0 g 0.0 b 0.0 a 0.0}}]
  (unchecked-int
    (bit-or (bit-shift-left (Math/round (* 255.0 (double a))) 24)
            (bit-shift-left (Math/round (* 255.0 (double r))) 16)
            (bit-shift-left (Math/round (* 255.0 (double g))) 8)
            (Math/round (* 255.0 (double b))))))

(defn- argb255
  "Pack a color into an ARGB int. Accepts an already-packed int, a
   {:r :g :b :a} map, or a [r g b] / [r g b a] vector — all 0-255 components.
   `alpha-override`, if given, replaces the color's own alpha channel."
  ([color] (argb255 color nil))
  ([color alpha-override]
   (cond
     (integer? color) (unchecked-int color)
     (map? color)
     (let [{:keys [r g b a] :or {r 255 g 255 b 255 a 255}} color]
       (unchecked-int (bit-or (bit-shift-left (long (or alpha-override a)) 24)
                              (bit-shift-left (long r) 16)
                              (bit-shift-left (long g) 8)
                              (long b))))
     (vector? color)
     (let [[r g b a] color]
       (unchecked-int (bit-or (bit-shift-left (long (or alpha-override a 255)) 24)
                              (bit-shift-left (long r) 16)
                              (bit-shift-left (long g) 8)
                              (long b))))
     :else (unchecked-int 0xFFFFFFFF))))

(defn- scale-alpha
  "Rescale an already-packed ARGB int's alpha channel by a 0.0-1.0 multiplier."
  [argb mult]
  ;; Zero-extend first: a negative packed int (top bit set, e.g. 0xFFFFFFFF)
  ;; sign-extends to all-ones under (long argb), which would poison every
  ;; byte extracted below.
  (let [u (bit-and (long argb) 0xFFFFFFFF)
        a (bit-and (unsigned-bit-shift-right u 24) 0xFF)
        a' (long (* a (double (or mult 1.0))))]
    (unchecked-int (bit-or (bit-shift-left a' 24) (bit-and u 0x00FFFFFF)))))

;; ---------------------------------------------------------------------------
;; Field adapters — each turns one reactive-hud/build-snapshot field into
;; either a rect map (for the :quad node type) or a vector of composite-list
;; items ({:kind :quad, :x :y :w :h :rgba} / {:kind :image :src :x :y :w :h
;; :rgba} / {:kind :text :text :x :y :rgba}).
;; ---------------------------------------------------------------------------

(defn- background-mask-rect [snapshot]
  (when-let [mask (:background-mask snapshot)]
    (when (pos? (double (:a mask 0.0)))
      {:rgba (argb01 mask)})))

(defn- cp-full-glow-items [snapshot]
  (let [cp-bar (:cp-bar snapshot)]
    (when (:full-glow? cp-bar)
      [{:kind :quad :x (:x cp-bar 8) :y (:y cp-bar 8)
        :w (:width cp-bar 100) :h (:height cp-bar 10)
        :rgba (unchecked-int 0x66FFFFFF)}])))

(defn- movement-hints-items [{:keys [x y items]}]
  (let [icon-size 14 gap 18]
    (vec
      (mapcat (fn [idx {:keys [key-label skill-icon active?]}]
                (let [row-y (+ y (* idx gap))]
                  [{:kind :image :src skill-icon :x x :y row-y :w icon-size :h icon-size
                    :rgba (if active? (unchecked-int 0xFFFFFFFF) (unchecked-int 0x80FFFFFF))}
                   {:kind :text :text (str key-label) :x (+ x icon-size 4) :y (+ row-y 3)
                    :rgba (if active? (unchecked-int 0xFFFFFFFF) (unchecked-int 0x80CCCCCC))}]))
              (range (count items)) items))))

(defn- activation-indicator-items [{:keys [y activated hint]}]
  (let [dot-x 4 dot-size 6
        dot-color (if activated (unchecked-int 0xFF3DD16A) (unchecked-int 0xFF808080))]
    (cond-> [{:kind :quad :x dot-x :y y :w dot-size :h dot-size :rgba dot-color}]
      (seq hint) (conj {:kind :text :text (str hint) :x (+ dot-x dot-size 4) :y y
                        :rgba (unchecked-int 0xFFFFFFFF)}))))

(defn- preset-indicator-dot-items [{:keys [current total fade]}]
  (let [box 10 step 14 base-x 8 base-y 40
        alpha (unchecked-int (max 0 (min 255 (Math/round (* 255.0 (double (or fade 1.0)))))))]
    (vec
      (for [i (range (long (or total 4)))
            :let [selected? (= i current)
                  color (argb255 (if selected? [255 235 120] [90 90 90]) alpha)]]
        {:kind :quad :x (+ base-x (* i step)) :y base-y :w box :h box :rgba color}))))

(defn- preset-indicators-items [indicators]
  (vec (mapcat preset-indicator-dot-items indicators)))

(defn- numbers-texts-items [texts]
  (mapv (fn [{:keys [text x y color]}]
          {:kind :text :text text :x x :y y :rgba (argb255 color)})
        texts))

(defn- vm-wave-items [waves]
  (mapv (fn [{:keys [src tint x y w h alpha]}]
          {:kind :image :src src :x x :y y :w w :h h
           :rgba (scale-alpha (argb255 tint) alpha)})
        waves))

(defn- charging-rect [snapshot]
  (when-let [{:keys [dim-a]} (:charging snapshot)]
    (when (and dim-a (pos? (long dim-a)))
      {:rgba (argb255 [0 0 0 (long dim-a)])})))

(defn- charging-arc-items [arcs]
  (mapv (fn [{:keys [src tint x y w h alpha]}]
          {:kind :image :src src :x x :y y :w w :h h
           :rgba (scale-alpha (argb255 tint) alpha)})
        arcs))

(defn- coin-qte-items [{:keys [bg-disc dots marker pct-text]}]
  (vec
    (concat
      (when bg-disc [{:kind :quad :x (:x bg-disc) :y (:y bg-disc) :w (:w bg-disc) :h (:h bg-disc)
                      :rgba (argb255 (:color bg-disc))}])
      (mapv (fn [{:keys [x y w h src tint alpha]}]
              {:kind :image :src src :x x :y y :w w :h h
               :rgba (scale-alpha (argb255 tint) alpha)})
            dots)
      (when marker [{:kind :quad :x (:x marker) :y (:y marker) :w (:w marker) :h (:h marker)
                     :rgba (argb255 (:color marker))}])
      (when pct-text [{:kind :text :text (:text pct-text) :x (:x pct-text) :y (:y pct-text)
                       :rgba (argb255 (:color pct-text))}]))))

(defn- toast-items [toasts]
  (vec
    (mapcat
      (fn [{:keys [x y w h bg borders text]}]
        (concat
          [{:kind :quad :x x :y y :w w :h h :rgba (argb255 bg)}]
          (mapv (fn [{:keys [x y w h a]}]
                  {:kind :quad :x x :y y :w w :h h :rgba (argb255 [255 255 255] a)})
                borders)
          (when text [{:kind :text :text (:text text) :x (:x text) :y (:y text)
                       :rgba (argb255 (:color text))}])))
      toasts)))

(defn- tutorial-notification-items [{:keys [bg icon title content]}]
  (vec
    (keep identity
      [(when bg {:kind :image :src (:src bg) :x (:x bg) :y (:y bg) :w (:w bg) :h (:h bg)
                :rgba (argb255 [255 255 255] (Math/round (* 255.0 (double (:alpha bg 1.0)))))})
       (when icon {:kind :image :src (:src icon) :x (:x icon) :y (:y icon) :w (:w icon) :h (:h icon)
                  :rgba (argb255 [255 255 255] (Math/round (* 255.0 (double (:alpha icon 1.0)))))})
       (when title {:kind :text :text (:text title) :x (:x title) :y (:y title)
                    :rgba (argb255 (:color title))})
       (when content {:kind :text :text (:text content) :x (:x content) :y (:y content)
                      :rgba (argb255 (:color content))})])))

(defn- debug-line-items [lines]
  (mapv (fn [{:keys [x y text color]}]
          {:kind :text :text text :x x :y y :rgba (argb255 color)})
        lines))

(defn- overload-pulse-items [snapshot]
  (let [ol-bar (:overload-bar snapshot)
        intensity (:overload-pulse-intensity snapshot)]
    (when (and ol-bar intensity (pos? (double intensity)))
      [{:kind :quad :x (:x ol-bar 8) :y (:y ol-bar 22)
        :w (:width ol-bar 100) :h (:height ol-bar 10)
        :rgba (argb255 [255 60 60] (Math/round (min 200.0 (* 200.0 (double intensity)))))}])))

(defn- screen-flash-rect [snapshot]
  (let [alpha (double (or (:screen-flash-alpha snapshot) 0.0))]
    (when (pos? alpha)
      {:rgba (argb255 [255 255 255] (Math/round (* 255.0 (min 1.0 alpha))))})))

(defn- interfered-items [snapshot]
  (when (:interfered? snapshot)
    [{:kind :quad :x 4 :y 34 :w 6 :h 6 :rgba (unchecked-int 0xFFDD3333)}
     {:kind :text :text "interfered" :x 14 :y 34 :rgba (unchecked-int 0xFFFF6666)}]))

(defn- binding-value [snapshot id]
  (case (int id)
    0 (get-in snapshot [:cp-bar :percent] 0.0)
    1 (get-in snapshot [:overload-bar :percent] 0.0)
    2 (:skill-slots snapshot [])
    3 (:selected-skill snapshot)
    4 (mapv #(select-keys % [:skill-id :cooldown-remaining :cooldown-total])
            (:skill-slots snapshot []))
    5 (:crosshair snapshot)
    6 (screen-flash-rect snapshot)
    7 (background-mask-rect snapshot)
    8 (cp-full-glow-items snapshot)
    9 (when-let [hints (:movement-hints snapshot)] (movement-hints-items hints))
    10 (when-let [indicator (:activation-indicator snapshot)] (activation-indicator-items indicator))
    11 (preset-indicators-items (:preset-indicators snapshot []))
    12 (numbers-texts-items (:numbers-texts snapshot []))
    13 (vm-wave-items (:vm-waves snapshot []))
    14 (charging-rect snapshot)
    15 (charging-arc-items (:charging-arcs snapshot []))
    16 (when-let [coin (:coin-qte snapshot)] (coin-qte-items coin))
    17 (toast-items (:toasts snapshot []))
    18 (when-let [notif (:tutorial-notification snapshot)] (tutorial-notification-items notif))
    19 (debug-line-items (:debug-lines snapshot []))
    20 (overload-pulse-items snapshot)
    21 (interfered-items snapshot)
    nil))

(defn- binding-table [snapshot-atom]
  (reify BindingTable
    (value [_ id] (binding-value @snapshot-atom id))))

;; :selected-skill and :skill-wheel-open? are UI-only state (which wedge is
;; highlighted, whether the wheel overlay is open) — reactive-hud/build-snapshot
;; never sets either, so refresh! must not clobber them when it pulls in a new
;; server-derived snapshot every frame.
(def ^:private ui-only-keys [:selected-skill :skill-wheel-open?])

(defn combat-view-model
  "Create the ViewModel for one local player. `dispatch-action!` is injected by
   AC so the core Runtime never knows game-specific skill semantics."
  [player-uuid dispatch-action!]
  (let [snapshot (atom {})
        last-action (atom nil)
        bindings (binding-table snapshot)
        model (reify PresentationViewModel
                (bindings [_] bindings)
                (^ActionResult dispatch [_ ^ActionId action ^ActionPayload payload]
                  ;; payload is the raw Java ActionPayload wrapper (empty or a
                  ;; capped byte array) — there is no decoder for structured
                  ;; click data (e.g. "which wedge") on this path yet, so
                  ;; :combat/select-skill cycles rather than jumping to an
                  ;; index the payload can't actually carry today.
                  (let [action-key (get action-ids (.value action))]
                    (case action-key
                      :combat/select-skill
                      (do (swap! snapshot (fn [s]
                                            (let [n (max 1 (count (:skill-slots s [])))
                                                  current (long (or (:selected-skill s) -1))]
                                              (assoc s :selected-skill (mod (inc current) n)))))
                          (reset! last-action [action-key payload])
                          (dispatch-action! action-key payload)
                          (ActionResult/accepted))

                      :combat/toggle-skill-wheel
                      (do (swap! snapshot update :skill-wheel-open? not)
                          (reset! last-action [action-key payload])
                          (dispatch-action! action-key payload)
                          (ActionResult/accepted))

                      nil (ActionResult/rejected "unknown combat HUD action")

                      (do (reset! last-action [action-key payload])
                          (dispatch-action! action-key payload)
                          (ActionResult/accepted)))))
                (close [_] (reset! snapshot {})))]
    {:model model
     :snapshot snapshot
     :bindings bindings
     :last-action last-action
     :refresh! (fn [screen-w screen-h opts]
                 (let [ui-state (select-keys @snapshot ui-only-keys)]
                   (reset! snapshot (merge (reactive-hud/build-snapshot player-uuid screen-w screen-h opts)
                                           ui-state)))
                 @snapshot)}))

(defn mount-combat-hud!
  "Mount the HUD through the version-neutral bridge. The bridge owns host
   lifecycle and Runtime state; AC only supplies template/model/content."
  [runtime player-uuid screen-w screen-h opts dispatch-action!]
  (let [{:keys [model refresh!] :as vm} (combat-view-model player-uuid dispatch-action!)
        _ (refresh! screen-w screen-h opts)
        handle (bridge/mount-host! runtime :combat-hud :hud "academy:combat_hud" model)]
    (assoc vm :mount handle)))
