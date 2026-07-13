(ns cn.li.ac.ability.client.arc-patterns
  "Zigzag lightning arc patterns + wiggle animation matching original
  AcademyCraft ArcPatterns (weakArc, strongArc, aoeArc, chargingArc,
  thinContiniousArc) and EntityArc texWiggle/showWiggle/hideWiggle.

  Each pattern defines the look of one arc type:
    :segments     — zigzag subdivision count (more = smoother but heavier)
    :amplitude    — max perpendicular deviation from straight line (0-1)
    :width        — total arc billboard width in world units
    :core-ratio   — inner core width / total width
    :tex-wiggle   — texture UV wiggle amplitude (sin-based)
    :show-wiggle  — apperance-phase wiggle start value
    :hide-wiggle  — disappearance-phase wiggle end value
    :life-ticks   — default lifetime in ticks
    :color-outer  — outer glow color {:r :g :b}
    :color-inner  — inner core color {:r :g :b}
    :color-line   — center line color {:r :g :b}
    :flicker      — probability arc is visible this frame (1.0 = always)
    :fork-count   — max number of side branches
    :fork-length  — fraction of main beam for fork reach
    :fork-angle   — radians, max deviation from beam axis"
  (:require [cn.li.ac.ability.client.effects.rv3 :as v]))

;; ============================================================================
;; ArcPattern presets — matching original AcademyCraft ArcPatterns constants
;; ============================================================================

(def arc-patterns
  {:weak
   {:name          "weakArc"
    :segments      8
    :amplitude     0.15
    :width         0.04
    :core-ratio    0.45
    :tex-wiggle    0.7
    :show-wiggle   0.1
    :hide-wiggle   0.4
    :life-ticks    10
    :color-outer   {:r 110 :g 190 :b 255}
    :color-inner   {:r 210 :g 235 :b 255}
    :color-line    {:r 180 :g 225 :b 255}
    :flicker       1.0
    :fork-count    0
    :fork-length   0.5
    :fork-angle    0.5}

   :strong
   {:name          "strongArc"
    :segments      14
    :amplitude     0.25
    :width         0.07
    :core-ratio    0.4
    :tex-wiggle    0.5
    :show-wiggle   0.15
    :hide-wiggle   0.3
    :life-ticks    20
    :color-outer   {:r 130 :g 210 :b 255}
    :color-inner   {:r 230 :g 245 :b 255}
    :color-line    {:r 200 :g 240 :b 255}
    :flicker       0.95
    :fork-count    2
    :fork-length   0.45
    :fork-angle    0.5}

   :aoe
   {:name          "aoeArc"
    :segments      10
    :amplitude     0.2
    :width         0.05
    :core-ratio    0.4
    :tex-wiggle    0.6
    :show-wiggle   0.1
    :hide-wiggle   0.35
    :life-ticks    20                      ;; ranged 15-25 in original
    :color-outer   {:r 120 :g 200 :b 255}
    :color-inner   {:r 220 :g 240 :b 255}
    :color-line    {:r 190 :g 230 :b 255}
    :flicker       0.9
    :fork-count    3
    :fork-length   0.5
    :fork-angle    0.6}

   :charging
   {:name          "chargingArc"
    :segments      16
    :amplitude     0.3
    :width         0.06
    :core-ratio    0.4
    :tex-wiggle    0.8
    :show-wiggle   0.2
    :hide-wiggle   0.8
    :life-ticks    100000                   ;; effectively infinite (manual stop)
    :color-outer   {:r 140 :g 220 :b 255}
    :color-inner   {:r 240 :g 250 :b 255}
    :color-line    {:r 210 :g 245 :b 255}
    :flicker       0.85
    :fork-count    2
    :fork-length   0.4
    :fork-angle    0.4}

   :thin-continuous
   {:name          "thinContiniousArc"
    :segments      20
    :amplitude     0.1
    :width         0.03
    :core-ratio    0.45
    :tex-wiggle    1.0
    :show-wiggle   0.1
    :hide-wiggle   0.6
    :life-ticks    100000                   ;; effectively infinite
    :color-outer   {:r 100 :g 180 :b 255}
    :color-inner   {:r 200 :g 230 :b 255}
    :color-line    {:r 170 :g 215 :b 255}
    :flicker       0.9
    :fork-count    0
    :fork-length   0.4
    :fork-angle    0.3}

   :railgun
   {:name          "railgunArc"
    :segments      12
    :amplitude     0.35
    :width         0.10
    :core-ratio    0.35
    :tex-wiggle    0.4
    :show-wiggle   0.2
    :hide-wiggle   0.2
    :life-ticks    30
    :color-outer   {:r 160 :g 230 :b 255}
    :color-inner   {:r 255 :g 255 :b 255}
    :color-line    {:r 220 :g 250 :b 255}
    :flicker       0.98
    :fork-count    4
    :fork-length   0.55
    :fork-angle    0.7}})

(defn get-pattern
  "Look up a named arc pattern. Falls back to :weak."
  [pattern-key]
  (get arc-patterns pattern-key (get arc-patterns :weak)))

;; ============================================================================
;; Zigzag path generation
;; ============================================================================

(defn generate-zigzag-segments
  "Generate zigzag vertex path from `start` to `end`.
  Returns vector of {:pos {:x :y :z} :u :v} entries forming the arc path.

  Each vertex is offset perpendicular to the beam direction by a pseudo-random
  amount (deterministic via :seed) to create the classic lightning-bolt shape.
  The amplitude controls how far vertices deviate.

  Matching original EntityArc zigzag rendering:
  - Segments subdivide the straight line into jagged sections
  - Each vertex has UV coords (u=0.0-1.0 along beam, v=0.0-1.0 across width)"
  [^cn.li.mcmod.math.V3 start ^cn.li.mcmod.math.V3 end
   {:keys [segments amplitude seed]
    :or {segments 8 amplitude 0.15 seed 42}}]
  (let [dir (v/vnorm (v/v- end start))
        len (v/vlen (v/v- end start))
        ;; Compute perpendicular axis (any axis perpendicular to dir works)
        up (if (< (Math/abs (.-y ^cn.li.mcmod.math.V3 dir)) 0.99) v/unit-y v/unit-x)
        perp (v/vnorm (v/vcross dir up))
        perp2 (v/vnorm (v/vcross dir perp))
        n (max 2 (int segments))]
    (loop [i 0
           vertices [{:pos start :u 0.0 :v 0.0}]]
      (if (>= i n)
        (conj vertices {:pos end :u 1.0 :v 0.0})
        (let [t (/ (inc i) (inc (double n)))
              pt (v/v+ start (v/v* (v/v- end start) t))
              ;; Deterministic pseudo-random offset based on seed + index
              r1 (Math/sin (+ (* seed 12.9898) (* i 78.233)))
              r2 (Math/cos (+ (* seed 45.1641) (* i 93.117)))
              offset1 (* amplitude len (Math/sin (* r1 Math/PI)))
              offset2 (* amplitude len 0.5 (Math/cos (* r2 Math/PI)))
              offset (v/v+ (v/v* perp offset1) (v/v* perp2 offset2))
              pt-offset (v/v+ pt offset)]
          (recur (inc i)
                 (conj vertices {:pos pt-offset :u t :v 0.0})))))))

;; ============================================================================
;; Wiggle animation state
;; ============================================================================

(defn- current-wiggle-time []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn wiggle-phase
  "Current global wiggle phase value, computed from wall-clock time.
  Integral of: 0.15 * (1 + 0.5*sin(3t))
  P(t) = 0.15*t + 0.025*(1 - cos(3t))"
  []
  (let [t (current-wiggle-time)]
    (+ (* 0.15 t)
       (* 0.025 (- 1.0 (Math/cos (* 3.0 t)))))))

(defn- lerp [a b t]
  (+ a (* t (- b a))))

(defn current-wiggle-offset
  "Compute UV wiggle offset for an arc segment based on pattern and life ratio.

  life-ratio: 0.0 (just spawned) to 1.0 (about to die).

  Matching original EntityArc showWiggle/hideWiggle transitions:
    - First 15% of life: wiggle ramps from showWiggle → texWiggle
    - Middle 55%: wiggle stays at texWiggle
    - Last 30%: wiggle ramps from texWiggle → hideWiggle

  Returns wiggle offset value to add to UV u-coordinate."
  [pattern life-ratio]
  (let [show-phase     (min 1.0 (/ (max 0.0 (double life-ratio)) 0.15))
        hide-phase     (max 0.0 (/ (- (max 0.0 (double life-ratio)) 0.7) 0.3))
        tex-wiggle     (double (or (:tex-wiggle pattern) 0.5))
        show-wiggle    (double (or (:show-wiggle pattern) 0.1))
        hide-wiggle    (double (or (:hide-wiggle pattern) 0.4))
        effective-wiggle (cond
                           (> hide-phase 0.0)
                           (lerp tex-wiggle hide-wiggle (min 1.0 hide-phase))
                           (< show-phase 1.0)
                           (lerp show-wiggle tex-wiggle show-phase)
                           :else tex-wiggle)
        phase          (wiggle-phase)
        segment-idx    0]  ;; caller provides this per-segment
    (* effective-wiggle (Math/sin (+ phase (* segment-idx 0.5))))))

(defn segment-wiggle-offset
  "UV wiggle offset for a specific segment along the arc.
  segment-t: position along arc 0.0-1.0
  pattern: arc pattern map
  life-ratio: 0.0-1.0"
  [segment-t pattern life-ratio]
  (let [show-phase     (min 1.0 (/ (max 0.0 (double life-ratio)) 0.15))
        hide-phase     (max 0.0 (/ (- (max 0.0 (double life-ratio)) 0.7) 0.3))
        tex-wiggle     (double (or (:tex-wiggle pattern) 0.5))
        show-wiggle    (double (or (:show-wiggle pattern) 0.1))
        hide-wiggle    (double (or (:hide-wiggle pattern) 0.4))
        effective-wiggle (cond
                           (> hide-phase 0.0)
                           (lerp tex-wiggle hide-wiggle (min 1.0 hide-phase))
                           (< show-phase 1.0)
                           (lerp show-wiggle tex-wiggle show-phase)
                           :else tex-wiggle)
        phase          (wiggle-phase)
        ;; Each segment along the arc has a different wiggle phase
        seg-phase      (+ phase (* segment-t 3.0))]
    (* effective-wiggle (Math/sin seg-phase))))

;; ============================================================================
;; Color helpers
;; ============================================================================

(defn pattern-color
  "Get a color from pattern with alpha applied.
  Returns RGBA integer suitable for render ops."
  [pattern color-key alpha]
  (let [{:keys [r g b]} (get pattern color-key {:r 255 :g 255 :b 255})
        a (int (max 0 (min 255 (long alpha))))]
    (unchecked-int (bit-or (bit-shift-left a 24)
                           (bit-shift-left (int r) 16)
                           (bit-shift-left (int g) 8)
                           (int b)))))

(defn life-fade-alpha
  "Compute fade alpha based on life ratio.
  Matching original EntityArc alpha curve:
    - Sharp fade-in over first 20% of life
    - Full brightness for middle 60%
    - Gradual fade-out over last 20%"
  [base-alpha life-ratio]
  (let [lr (max 0.0 (min 1.0 (double life-ratio)))
        fade-in  (min 1.0 (/ lr 0.2))
        fade-out (min 1.0 (/ (- 1.0 lr) 0.2))
        alpha-factor (* fade-in fade-out)
        raw-alpha (* (double base-alpha) alpha-factor)]
    (int (max 0 (min 255 (long raw-alpha))))))
