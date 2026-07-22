(ns cn.li.ac.ability.client.render-util
  "Client-side rendering primitives for ability visual effects.

  Provides vector math, render-op constructors, camera-basis helpers,
  zigzag arc ops (matching original EntityArc/ArcPatterns), and
  billboard-beam ops that are shared across all skill effect renderers.

  Uses V3 (rv3), not the map-based cn.li.ac.util.math.vec3 — this is the
  per-frame render hot path; vec3.clj stays map-based for its other 28
  non-render consumers (see rv3.clj docstring)."
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.ac.ability.client.arc-patterns :as arc])
  (:import [cn.li.mcmod.math V3]))

;; ---------------------------------------------------------------------------
;; Color helpers
;; ---------------------------------------------------------------------------

(defn with-alpha
  "Assoc :a onto a color map, clamped to [0, 255]."
  [color alpha]
  (assoc color :a (int (max 0 (min 255 (long alpha))))))

;; ---------------------------------------------------------------------------
;; Render op constructors
;; ---------------------------------------------------------------------------

(defn quad-op
  "Build a textured-quad render op."
  [texture p0 p1 p2 p3 color]
  {:kind :quad
   :texture texture
   :p0 p0 :p1 p1 :p2 p2 :p3 p3
   :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
   :color color})

(defn line-op
  "Build a line-segment render op."
  [p1 p2 color]
  {:kind :line :p1 p1 :p2 p2 :color color})

(def ^:private default-beam-texture
  (modid/asset-path "textures" "effects/arc.png"))

(declare beam-right-axis)

(defn- rand-offset
  "Random vec3 offset within magnitude."
  ^V3 [magnitude]
  (when (pos? (double magnitude))
    (vec3/v3 (* magnitude (- (* 2.0 (rand)) 1.0))
             (* magnitude (- (* 2.0 (rand)) 1.0))
             (* magnitude (- (* 2.0 (rand)) 1.0)))))

(defn zigzag-arc-ops
  "Generate zigzag lightning arc render ops with deterministic sin-based wiggle,
  matching original AcademyCraft EntityArc + ArcPatterns visual style.

  Differences from billboard-beam-ops:
    - Zigzag path from arc-patterns/generate-zigzag-segments (not random jitter)
    - Deterministic sin-based UV wiggle per segment (not random endpoint offet)
    - Multi-segment quads along the zigzag path (not single beam quad)
    - Life-ratio based fade and wiggle transitions (showWiggle/hideWiggle)

  Params:
    :arc-pattern  ??keyword from arc-patterns/arc-patterns (:weak, :strong, etc.)
    :life-ratio   ??0.0 (just spawned) to 1.0 (about to die)
    :texture      ??override texture path (default: effects/arc.png)
    :wiggle-offset ??optional per-call wiggle phase override"
  [cam-pos start end {:keys [arc-pattern life-ratio texture
                             wiggle-offset]
                      :or {arc-pattern :weak life-ratio 0.5}}]
  (let [pattern   (arc/get-pattern arc-pattern)
        texture   (or texture default-beam-texture)
        lr        (double life-ratio)
        ;; Generate zigzag path
        vertices  (arc/generate-zigzag-segments start end pattern)
        ;; Alpha based on life ratio
        outer-alpha (arc/life-fade-alpha 180 lr)
        inner-alpha (arc/life-fade-alpha 220 lr)
        line-alpha  (arc/life-fade-alpha 160 lr)
        outer-color (arc/pattern-color pattern :color-outer outer-alpha)
        inner-color (arc/pattern-color pattern :color-inner inner-alpha)
        line-color  (arc/pattern-color pattern :color-line line-alpha)
        width       (double (or (:width pattern) 0.15))
        core-ratio  (double (or (:core-ratio pattern) 0.45))
        core-width  (* width core-ratio)
        ;; Per-segment quads
        segment-count (dec (count vertices))]
    (if (< segment-count 1)
      ;; Single quad fallback for degenerate paths
      (let [right (beam-right-axis start end cam-pos)
            outer-o (vec3/v* right width)
            core-o  (vec3/v* right core-width)]
        [(quad-op texture (vec3/v+ start outer-o) (vec3/v- start outer-o)
                  (vec3/v- end outer-o) (vec3/v+ end outer-o) outer-color)
         (quad-op texture (vec3/v+ start core-o) (vec3/v- start core-o)
                  (vec3/v- end core-o) (vec3/v+ end core-o) inner-color)
         (line-op start end line-color)])
      ;; Multi-segment zigzag
      (let [seg-quads
            (mapcat (fn [i]
                      (let [v0 (nth vertices i)
                            v1 (nth vertices (inc i))
                            seg-start (:pos v0)
                            seg-end   (:pos v1)
                            seg-t     (:u v0 0.0)
                            right (beam-right-axis seg-start seg-end cam-pos)
                            wiggle (arc/segment-wiggle-offset seg-t pattern lr)
                            outer-o (vec3/v* right width)
                            core-o  (vec3/v* right core-width)
                            p0 (vec3/v+ seg-start outer-o)
                            p1 (vec3/v- seg-start outer-o)
                            p2 (vec3/v- seg-end outer-o)
                            p3 (vec3/v+ seg-end outer-o)
                            c0 (vec3/v+ seg-start core-o)
                            c1 (vec3/v- seg-start core-o)
                            c2 (vec3/v- seg-end core-o)
                            c3 (vec3/v+ seg-end core-o)
                            u0-seg (+ (:u v0 0.0) wiggle)
                            u1-seg (+ (:u v1 0.0) wiggle)]
                        [(assoc (quad-op texture p0 p1 p2 p3 outer-color)
                                :u0 (double u0-seg) :u1 (double u1-seg)
                                :v0 0.0 :v1 1.0)
                         (assoc (quad-op texture c0 c1 c2 c3 inner-color)
                                :u0 (double u0-seg) :u1 (double u1-seg)
                                :v0 0.0 :v1 1.0)
                         (line-op seg-start seg-end line-color)]))
                    (range segment-count))
            fork-count   (int (or (:fork-count pattern) 0))
            fork-length  (double (or (:fork-length pattern) 0.5))
            fork-angle   (double (or (:fork-angle pattern) 0.5))
            fork-quads
            (when (pos? fork-count)
              (let [n (inc (rand-int fork-count))]
                (mapcat (fn [_]
                          (let [t (rand)
                                mid (vec3/v+ start (vec3/v* (vec3/v- end start) t))
                                dir (vec3/vnorm (vec3/v- end start))
                                perp1 (beam-right-axis start end cam-pos)
                                perp2 (if (> (vec3/vlen perp1) 0.01)
                                        (vec3/vnorm (vec3/vcross dir perp1))
                                        vec3/unit-x)
                                angle (* fork-angle (- (* 2.0 (rand)) 1.0))
                                rot-dir (vec3/v+ (vec3/v* perp1 (Math/cos angle))
                                            (vec3/v* perp2 (Math/sin angle)))
                                fork-end (vec3/v+ mid (vec3/v* rot-dir (* (vec3/vlen (vec3/v- end start)) fork-length)))
                                fork-w (* width 0.5)
                                fork-c (* fork-w 0.4)
                                fr (beam-right-axis mid fork-end cam-pos)
                                fo (vec3/v* fr fork-w)
                                fc (vec3/v* fr fork-c)
                                fork-alpha (int (* outer-alpha 0.6))]
                            [(quad-op texture
                               (vec3/v+ mid fo) (vec3/v- mid fo)
                               (vec3/v- fork-end fo) (vec3/v+ fork-end fo)
                               (arc/pattern-color pattern :color-outer fork-alpha))
                             (line-op mid fork-end
                               (arc/pattern-color pattern :color-line (int (* line-alpha 0.5))))]))
                        (range n))))]
        (vec (concat seg-quads fork-quads))))))

(defn billboard-beam-ops
  "Build ability beam primitives matching original ArcPatterns visual style:
  - Textured quad (arc.png) with jitter and flicker for lightning effect
  - Inner core quad for intensity
  - Optional center line
  - Optional forked side branches for branched lightning look

  New params:
    :jitter-amount     ??world-space random endpoint offset (default 0, no jitter)
    :flicker-threshold ??0-1 probability arc is visible this frame (default nil, always visible)
    :fork-count        ??max number of side branches to generate (default 0)
    :fork-length       ??fraction [0-1] of main beam length for fork reach (default 0.5)
    :fork-angle        ??radians, max deviation from beam axis for forks (default 0.5)
    :fork-width-frac   ??fraction of main width for fork beams (default 0.5)"
  [cam-pos start end {:keys [texture width core-width core-ratio
                              outer-color inner-color line-color
                              jitter-amount flicker-threshold
                              fork-count fork-length fork-angle fork-width-frac]}]
  (let [texture (or texture default-beam-texture)
        width (double (or width 0.0))
        ;; Jitter: randomize endpoint to simulate arc wiggle
        jitter-amount (double (or jitter-amount 0.0))
        end-jitter (rand-offset jitter-amount)
        start-jitter (rand-offset (* jitter-amount 0.3)) ;; less jitter at origin
        start' (if (pos? jitter-amount)
                 (vec3/v+ start start-jitter)
                 start)
        end' (if (pos? jitter-amount)
               (vec3/v+ end end-jitter)
               end)
        ;; Flicker: randomly skip rendering this frame
        flicker (or flicker-threshold 1.0)
        _visible? (or (>= flicker 1.0) (< (rand) flicker))
        core-width (double (or core-width (* width (double (or core-ratio 0.45)))))
        right (beam-right-axis start' end' cam-pos)
        outer-offset (vec3/v* right width)
        core-offset (vec3/v* right core-width)
        p0 (vec3/v+ start' outer-offset)
        p1 (vec3/v- start' outer-offset)
        p2 (vec3/v- end' outer-offset)
        p3 (vec3/v+ end' outer-offset)
        c0 (vec3/v+ start' core-offset)
        c1 (vec3/v- start' core-offset)
        c2 (vec3/v- end' core-offset)
        c3 (vec3/v+ end' core-offset)
        ;; Main beam quads (only if not flickered out)
        base-quads (if _visible?
                     [(quad-op texture p0 p1 p2 p3 outer-color)
                      (quad-op texture c0 c1 c2 c3 inner-color)]
                     [])
        ;; Forked side branches (matching original branched arc patterns)
        fork-count (int (or fork-count 0))
        fork-length (double (or fork-length 0.5))
        fork-angle (double (or fork-angle 0.5))
        fork-width-frac (double (or fork-width-frac 0.5))
        side-quads (when (and _visible? (pos? fork-count))
                     (let [fork-n (inc (rand-int fork-count))]
                       (mapcat (fn [i]
                                 (let [t (rand)
                                       mid-point (vec3/v+ start' (vec3/v* (vec3/v- end' start') t))
                                       dir (vec3/vnorm (vec3/v- end' start'))
                                       perp1 (beam-right-axis start' end' cam-pos)
                                       perp2 (if (> (vec3/vlen perp1) 0.01)
                                               (vec3/vnorm (vec3/vcross dir perp1))
                                               vec3/unit-x)
                                       angle (* fork-angle (- (* 2.0 (rand)) 1.0))
                                       rot-dir (vec3/v+ (vec3/v* perp1 (Math/cos angle))
                                                   (vec3/v* perp2 (Math/sin angle)))
                                       fork-end (vec3/v+ mid-point (vec3/v* rot-dir (* (vec3/vlen (vec3/v- end' start')) fork-length)))
                                       fork-w (* width fork-width-frac)
                                       fork-core (* fork-w 0.4)
                                       fr (beam-right-axis mid-point fork-end cam-pos)
                                       fo (vec3/v* fr fork-w)
                                       fc (vec3/v* fr fork-core)
                                       fork-alpha (with-alpha outer-color
                                                   (int (* (or (:a outer-color) 128) 0.6)))]
                                   [(quad-op texture
                                      (vec3/v+ mid-point fo) (vec3/v- mid-point fo)
                                      (vec3/v- fork-end fo) (vec3/v+ fork-end fo)
                                      fork-alpha)
                                    (quad-op texture
                                      (vec3/v+ mid-point fc) (vec3/v- mid-point fc)
                                      (vec3/v- fork-end fc) (vec3/v+ fork-end fc)
                                      (with-alpha inner-color
                                        (int (* (or (:a inner-color) 255) 0.4))))]))
                               (range fork-n))))]
    (cond-> (into base-quads side-quads)
      (and line-color _visible?) (conj (line-op start' end' line-color)))))

;; ---------------------------------------------------------------------------
;; Camera-relative basis helpers
;; ---------------------------------------------------------------------------

(defn beam-right-axis
  "Compute the right axis for a billboard beam between `start` and `end`,
  perpendicular to both the beam direction and the camera view direction."
  [start end cam-pos]
  (let [dir (vec3/vnorm (vec3/v- end start))
        mid (vec3/v* (vec3/v+ start end) 0.5)
        to-cam (vec3/vnorm (vec3/v- cam-pos mid))
        raw (vec3/vcross dir to-cam)]
    (if (> (vec3/vlen raw) 1.0e-5)
      (vec3/vnorm raw)
      vec3/unit-x)))

(defn camera-facing-right-axis
  "Right axis for a camera-facing billboard at `center`."
  [center cam-pos]
  (let [to-cam (vec3/vnorm (vec3/v- cam-pos center))
        raw (vec3/vcross vec3/unit-y to-cam)]
    (if (> (vec3/vlen raw) 1.0e-5)
      (vec3/vnorm raw)
      vec3/unit-x)))

(defn billboard-up-axis
  "Up axis for a billboard given `center`, `cam-pos`, and already-computed `right`."
  [center cam-pos right]
  (let [to-cam (vec3/vnorm (vec3/v- cam-pos center))
        raw (vec3/vcross to-cam right)]
    (if (> (vec3/vlen raw) 1.0e-5)
      (vec3/vnorm raw)
      vec3/unit-y)))
