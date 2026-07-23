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
  "Build a textured-quad render op. 6-arity uses full [0,1] UVs; 10-arity
  overrides them directly (avoids a second map allocation via assoc for
  UV-scrolling callers like zigzag-arc-ops)."
  ([texture p0 p1 p2 p3 color]
   (quad-op texture p0 p1 p2 p3 0.0 1.0 0.0 1.0 color))
  ([texture p0 p1 p2 p3 u0 u1 v0 v1 color]
   {:kind :quad
    :texture texture
    :p0 p0 :p1 p1 :p2 p2 :p3 p3
    :u0 u0 :u1 u1 :v0 v0 :v1 v1
    :color color}))

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
  "Generate zigzag lightning arc render ops matching original AcademyCraft
  EntityArc + ArcPatterns visual style.

  `vertices` is the precomputed zigzag path (arc-patterns/generate-zigzag-segments,
  computed once when the arc was enqueued — its shape is fixed for the arc's
  lifetime, so it is not recomputed per frame). `pattern` is the resolved
  arc-patterns/get-pattern map. `wiggle-phase` is the current global wiggle
  clock (arc-patterns/wiggle-phase) — compute once per plan build, not once
  per arc/segment. `effective-wiggle` is this arc's wiggle amplitude for its
  current life-ratio (arc-patterns/effective-wiggle-amount) — compute once
  per arc, not once per segment.

  Differences from billboard-beam-ops:
    - Zigzag path (not random jitter)
    - Deterministic sin-based UV wiggle per segment (not random endpoint offset)
    - Multi-segment quads along the zigzag path (not single beam quad)
    - Life-ratio based fade (showWiggle/hideWiggle handled via effective-wiggle)

  Params:
    :life-ratio        ??0.0 (just spawned) to 1.0 (about to die)
    :texture           ??override texture path (default: effects/arc.png)
    :wiggle-phase      ??current arc-patterns/wiggle-phase value
    :effective-wiggle  ??this arc's arc-patterns/effective-wiggle-amount value"
  [cam-pos vertices pattern {:keys [life-ratio texture wiggle-phase effective-wiggle]
                             :or {life-ratio 0.5 wiggle-phase 0.0 effective-wiggle 0.0}}]
  (let [texture     (or texture default-beam-texture)
        lr          (double life-ratio)
        outer-alpha (arc/life-fade-alpha 180 lr)
        inner-alpha (arc/life-fade-alpha 220 lr)
        line-alpha  (arc/life-fade-alpha 160 lr)
        outer-color (arc/pattern-color pattern :color-outer outer-alpha)
        inner-color (arc/pattern-color pattern :color-inner inner-alpha)
        line-color  (arc/pattern-color pattern :color-line line-alpha)
        width       (double (or (:width pattern) 0.15))
        core-ratio  (double (or (:core-ratio pattern) 0.45))
        core-width  (* width core-ratio)
        segment-count (dec (count vertices))
        seg-quads
        (mapcat (fn [i]
                  (let [v0 (nth vertices i)
                        v1 (nth vertices (inc i))
                        seg-start (:pos v0)
                        seg-end   (:pos v1)
                        seg-t     (:u v0 0.0)
                        right (beam-right-axis seg-start seg-end cam-pos)
                        wiggle (* effective-wiggle (Math/sin (+ wiggle-phase (* seg-t 3.0))))
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
                    [(quad-op texture p0 p1 p2 p3 u0-seg u1-seg 0.0 1.0 outer-color)
                     (quad-op texture c0 c1 c2 c3 u0-seg u1-seg 0.0 1.0 inner-color)
                     (line-op seg-start seg-end line-color)]))
                (range segment-count))
        fork-count   (int (or (:fork-count pattern) 0))
        fork-length  (double (or (:fork-length pattern) 0.5))
        fork-angle   (double (or (:fork-angle pattern) 0.5))
        fork-quads
        (when (pos? fork-count)
          (let [start (:pos (first vertices))
                end   (:pos (peek vertices))
                beam-vec (vec3/v- end start)
                beam-len (vec3/vlen beam-vec)
                dir (vec3/vnorm beam-vec)
                perp1 (beam-right-axis start end cam-pos)
                perp2 (if (> (vec3/vlen perp1) 0.01)
                        (vec3/vnorm (vec3/vcross dir perp1))
                        vec3/unit-x)
                n (inc (rand-int fork-count))]
            (mapcat (fn [_]
                      (let [t (rand)
                            mid (vec3/v+ start (vec3/v* beam-vec t))
                            angle (* fork-angle (- (* 2.0 (rand)) 1.0))
                            rot-dir (vec3/v+ (vec3/v* perp1 (Math/cos angle))
                                        (vec3/v* perp2 (Math/sin angle)))
                            fork-end (vec3/v+ mid (vec3/v* rot-dir (* beam-len fork-length)))
                            fork-w (* width 0.5)
                            fr (beam-right-axis mid fork-end cam-pos)
                            fo (vec3/v* fr fork-w)
                            fork-alpha (int (* outer-alpha 0.6))]
                        [(quad-op texture
                           (vec3/v+ mid fo) (vec3/v- mid fo)
                           (vec3/v- fork-end fo) (vec3/v+ fork-end fo)
                           (arc/pattern-color pattern :color-outer fork-alpha))
                         (line-op mid fork-end
                           (arc/pattern-color pattern :color-line (int (* line-alpha 0.5))))]))
                    (range n))))]
    (vec (concat seg-quads fork-quads))))

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
    :fork-width-frac   ??fraction of main width for fork beams (default 0.5)

  Flicker is resolved first: when the roll comes up invisible this frame
  (original behavior never emits a line in that case either), all jitter/
  billboard/fork computation is skipped instead of computed and discarded."
  [cam-pos start end {:keys [texture width core-width core-ratio
                              outer-color inner-color line-color
                              jitter-amount flicker-threshold
                              fork-count fork-length fork-angle fork-width-frac]}]
  (let [flicker (double (or flicker-threshold 1.0))]
    (if-not (or (>= flicker 1.0) (< (rand) flicker))
      []
      (let [texture (or texture default-beam-texture)
            width (double (or width 0.0))
            ;; Jitter: randomize endpoint to simulate arc wiggle
            jitter-amount (double (or jitter-amount 0.0))
            start' (if (pos? jitter-amount)
                     (vec3/v+ start (rand-offset (* jitter-amount 0.3))) ;; less jitter at origin
                     start)
            end' (if (pos? jitter-amount)
                   (vec3/v+ end (rand-offset jitter-amount))
                   end)
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
            base-quads [(quad-op texture p0 p1 p2 p3 outer-color)
                        (quad-op texture c0 c1 c2 c3 inner-color)]
            ;; Forked side branches (matching original branched arc patterns)
            fork-count (int (or fork-count 0))
            fork-length (double (or fork-length 0.5))
            fork-angle (double (or fork-angle 0.5))
            fork-width-frac (double (or fork-width-frac 0.5))
            side-quads (when (pos? fork-count)
                         (let [beam-vec (vec3/v- end' start')
                               beam-len (vec3/vlen beam-vec)
                               dir (vec3/vnorm beam-vec)
                               perp1 (beam-right-axis start' end' cam-pos)
                               perp2 (if (> (vec3/vlen perp1) 0.01)
                                       (vec3/vnorm (vec3/vcross dir perp1))
                                       vec3/unit-x)
                               fork-n (inc (rand-int fork-count))]
                           (mapcat (fn [_]
                                     (let [t (rand)
                                           mid-point (vec3/v+ start' (vec3/v* beam-vec t))
                                           angle (* fork-angle (- (* 2.0 (rand)) 1.0))
                                           rot-dir (vec3/v+ (vec3/v* perp1 (Math/cos angle))
                                                       (vec3/v* perp2 (Math/sin angle)))
                                           fork-end (vec3/v+ mid-point (vec3/v* rot-dir (* beam-len fork-length)))
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
          line-color (conj (line-op start' end' line-color)))))))

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
