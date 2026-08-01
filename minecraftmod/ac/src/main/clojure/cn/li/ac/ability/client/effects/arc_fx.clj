(ns cn.li.ac.ability.client.effects.arc-fx
  "L-system lightning arc generation for beam effects.
  Ported from original AcademyCraft ArcFactory.java + SubArcHandler.java."
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]))

;; ---------------------------------------------------------------------------
;; 3D vector helpers — generic ops delegate to the render-pipeline V3 library
;; (rv3, not the map-based util.math.vec3 — see rv3.clj docstring); yaw/pitch
;; rotation is arc_fx-specific, kept local.
;; ---------------------------------------------------------------------------

(def ^:private v+ vec3/v+)
(def ^:private v- vec3/v-)
(def ^:private v* vec3/v*)
(def ^:private v-cross vec3/vcross)
(def ^:private v-length vec3/vlen)
(def ^:private v-normalize vec3/vnorm)

(defn- v-rotate-yaw [^cn.li.mcmod.math.V3 a rad]
  (let [cos (Math/cos rad) sin (Math/sin rad)]
    (vec3/v3 (+ (* (.-x a) cos) (* (.-z a) sin))
             (.-y a)
             (- (* (.-z a) cos) (* (.-x a) sin)))))

(defn- v-rotate-pitch [^cn.li.mcmod.math.V3 a rad]
  (let [cos (Math/cos rad) sin (Math/sin rad)]
    (vec3/v3 (.-x a)
             (- (* (.-y a) cos) (* (.-z a) sin))
             (+ (* (.-z a) cos) (* (.-y a) sin)))))

(defn- random-rotate
  "Randomly rotate a vector within range degrees (matching original randomRotate).

  Upstream: `a = rangef(-range, range) / 180 * PI`. This read
  `(/ deg 180.0 Math/PI)` — dividing by pi where the degree-to-radian
  conversion multiplies — so every rotation came out pi^2 (~9.9x) too small.
  Branch directions barely deviated from their parent, and the random
  perpendicular segment->quads derives for billboarding was nearly parallel
  to the segment it came from, leaving the cross product close to zero."
  [range-deg dir]
  (let [a-rad (* (/ (* range-deg (rand)) 180.0) Math/PI)
        pitched (v-rotate-pitch dir (- (* (rand) 2 a-rad) a-rad))]
    (v-rotate-yaw pitched (- (* (rand) 2 a-rad) a-rad))))

(defn- random-dir-orthogonal
  "Generate a random direction in the plane orthogonal to the given vector."
  [^cn.li.mcmod.math.V3 dir offset]
  (let [theta (* (rand) 2.0 Math/PI)
        sin-theta (Math/sin theta)
        cos-theta (Math/cos theta)
        ;; Find two orthogonal basis vectors
        candidate (if (< (Math/abs (.-x dir)) 0.9) vec3/unit-x vec3/unit-y)
        u (v-normalize (v-cross dir candidate))
        w (v-normalize (v-cross dir u))]
    (v+ (v* u (* offset sin-theta))
        (v* w (* offset cos-theta)))))

;; ---------------------------------------------------------------------------
;; Arc segment data types
;; {:start {:pos V3 :width w} :end {:pos V3 :width w} :alpha}
;; ---------------------------------------------------------------------------

(defn- point [pos w] {:pos pos :width w})
(defn- point-avg [p1 p2]
  {:pos (v* (v+ (:pos p1) (:pos p2)) 0.5)
   :width (* 0.5 (+ (:width p1 0.1) (:width p2 0.1)))})

(defn- segment [start end alpha]
  {:start (if (:pos start) start (point start 0.1))
   :end   (if (:pos end)   end   (point end 0.1))
   :alpha alpha})

;; ---------------------------------------------------------------------------
;; L-system arc generation (matching original ArcFactory)
;; ---------------------------------------------------------------------------

(defn- handle-single-pass
  "Process one L-system pass over every arc: split each segment at a displaced
  midpoint, and fork a new arc with probability branch-factor.
  Returns {:arcs refined-arcs :branches new-arcs}."
  [arcs offset width-shrink alpha-shrink length-shrink branch-factor]
  (let [branches (transient [])
        refined
        (mapv (fn [segments]
                (vec
                 (mapcat (fn [s]
                           (let [ave (point-avg (:start s) (:end s))
                                 dir-vec (v- (:pos (:end s)) (:pos (:start s)))
                                 displace-dir (random-dir-orthogonal
                                               (v-normalize dir-vec)
                                               (* (rand) offset))
                                 ave-pt (point (v+ (:pos ave) displace-dir) (:width ave))
                                 s1 (assoc s :end ave-pt)
                                 s2 (segment ave-pt (:end s) (:alpha s))]
                             ;; Branching
                             (when (< (rand) branch-factor)
                               ;; Upstream forks along `ave.pt - s.start.pt`,
                               ;; i.e. the displaced HALF segment. Using the
                               ;; whole segment made every branch twice as
                               ;; long as intended.
                               (let [dir (v* (v- (:pos ave-pt) (:pos (:start s)))
                                             length-shrink)
                                     rdir (random-rotate 10.0 dir)
                                     w2 (* (:width ave-pt) width-shrink)
                                     bp1 (point (:pos ave-pt) w2)
                                     bp2 (point (v+ (:pos ave-pt) rdir) w2)]
                                 (conj! branches [(segment bp1 bp2 (* (:alpha s) alpha-shrink))])))
                             [s1 s2]))
                         segments)))
              arcs)]
    {:arcs refined :branches (persistent! branches)}))

(defn- generate-arc-segments
  "Generate L-system arc segments for a single arc template.
  Returns a vector of segment-lists (main trunk + all branches).

  Upstream ArcFactory keeps listAll and bufferAll as PARALLEL per-arc
  ping-pong buffers — index j of each is one arc's read/write pair, every arc
  is refined on every pass, and a fork appends a new arc to both. This port
  had collapsed them into two flat lists and, on the passes where it refined
  the branch list, handed the previous *unrefined* branch list back as the
  trunk. The trunk was therefore discarded after the first such pass, and a
  run that happened to fork nothing returned no segments at all — verified:
  with branch-factor 0 it produced zero.

  The ping-pong is a Java allocation trick; refining into fresh vectors needs
  no buffer swap, so passes here just refine every arc and append new forks."
  [length width max-offset passes branch-factor width-shrink]
  (let [v0 (vec3/v3 0.0 0.0 0.0)
        v1 (vec3/v3 length 0.0 0.0)
        init [(segment (point v0 width) (point v1 width) 1.0)]
        alpha-shrink 0.9
        length-shrink 0.7]
    (loop [arcs [init]
           offset max-offset
           pass 0]
      (if (>= pass passes)
        arcs
        (let [{:keys [arcs branches]}
              (handle-single-pass arcs offset width-shrink alpha-shrink
                                  length-shrink branch-factor)]
          (recur (into arcs branches)
                 (/ offset 2.0)
                 (inc pass)))))))

;; ---------------------------------------------------------------------------
;; Arc template cache (generated once, reused each frame)
;; ---------------------------------------------------------------------------

(def ^:private default-num-templates 15)
(def ^:private default-arc-length 1.5)
(def ^:private default-arc-width 0.3)
(def ^:private default-max-offset 0.8)
(def ^:private default-passes 3)
(def ^:private default-branch-factor 0.7)
(def ^:private default-width-shrink 0.9)

(def ^:private template-cache (object-array 1))

(defn- ensure-templates
  "Generate arc templates on first call, cache and return."
  []
  (or (aget ^objects template-cache 0)
      (let [templates
            (vec
             (for [_ (range default-num-templates)
                   :let [length (+ 2.0 (rand))]]
               (generate-arc-segments length
                 default-arc-width default-max-offset
                 default-passes default-branch-factor default-width-shrink)))]
        (aset ^objects template-cache 0 templates)
        templates)))

(defn reset-arc-templates-for-test!
  "Clear cached templates (for testing)."
  []
  (aset ^objects template-cache 0 nil)
  nil)

;; ---------------------------------------------------------------------------
;; Arc rendering: convert segments to quads
;; ---------------------------------------------------------------------------

(defn- segment->quads
  "Convert a list of arc segments to render ops (textured quads)."
  [segments]
  (let [texture (modid/asset-path "textures" "effects/arc/line_segment.png")]
    (vec
     (for [[{:keys [start end alpha]} seg-idx] (map vector segments (range))
           :let [start-pos (:pos start)
                 end-pos (:pos end)
                 dir-vec (v- end-pos start-pos)
                 ;; Create perpendicular direction for quad width
                 perp (random-rotate 15.0 dir-vec)
                 perp (v-normalize (v-cross dir-vec perp))
                 perp (v* perp 1.0)
                 half-w1 (* 0.5 (:width start 0.15))
                 half-w2 (* 0.5 (:width end 0.15))
                 p0 (v+ start-pos (v* perp half-w1))
                 p1 (v+ start-pos (v* perp (- half-w1)))
                 p2 (v+ end-pos (v* perp (- half-w2)))
                 p3 (v+ end-pos (v* perp half-w2))
                 r 255 g 255 b 255
                 a (int (min 255 (* 255 alpha)))]]
       {:kind :quad
        :texture texture
        :p0 p0 :p1 p1 :p2 p2 :p3 p3
        :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
        :color {:r r :g g :b b :a a}}))))

;; ---------------------------------------------------------------------------
;; Public API: descending strike bolt
;;
;; Vanilla's LightningBoltRenderer walks 8 fixed 16-block segments straight up
;; from the entity, jittering each joint by at most ±5. Over 128 blocks that
;; is a very shallow wander, which is why a vanilla bolt reads as a bright
;; vertical column rather than a forked strike. The generator below reuses the
;; same L-system as the small arcs but tuned for a tall descending channel:
;; many more joints, a displacement budget that halves each pass (fractal
;; midpoint displacement), and forks that branch off and taper out on the way
;; down.
;; ---------------------------------------------------------------------------

(def ^:private strike-defaults
  {:height 64.0          ;; tall enough to leave the top out of frame
   :width 0.55
   :max-offset 5.0       ;; halved per pass: 5, 2.5, 1.25, 0.625, 0.3125
   :passes 5             ;; 2^5 joints on the trunk — ~2 blocks per segment
   :branch-factor 0.16
   :branch-length 0.28   ;; fraction of the remaining drop
   :width-shrink 0.55})

(defn- midpoint-displace
  "One fractal midpoint-displacement pass: split every span and push the new
  joint sideways by up to `offset`."
  [pts offset]
  (conj (into []
              (mapcat (fn [[a b]]
                        (let [dir (v- b a)
                              mid (v* (v+ a b) 0.5)
                              push (random-dir-orthogonal (v-normalize dir)
                                                          (* offset (rand)))]
                          [a (v+ mid push)])))
              (partition 2 1 pts))
        (last pts)))

(defn- refine-channel
  [pts max-offset passes]
  (loop [pts pts
         offset (double max-offset)
         pass 0]
    (if (>= pass passes)
      pts
      (recur (midpoint-displace pts offset) (/ offset 2.0) (inc pass)))))

(defn- polyline->segments
  [pts width alpha]
  (mapv (fn [[a b]] (segment (point a width) (point b width) alpha))
        (partition 2 1 pts)))

(defn strike-bolt-segments
  "World-space segments for a bolt descending onto `impact` out of the sky.

  Fractal midpoint displacement on a sky-to-impact channel, plus forks that
  veer off and taper out on the way down.

  Deliberately not `generate-arc-segments`: that one alternates which list it
  refines and hands the unprocessed branch list back as the trunk, so the
  trunk is dropped after the first pass and a run with no branches returns
  nothing at all. That behaviour is load-bearing for the bushy little railgun
  arcs it was ported for, but a strike needs its channel to survive.

  Generate once per strike and keep the result — the displacement is
  randomised, so rebuilding per frame would make the channel crawl instead of
  holding its shape while it flickers."
  ([impact] (strike-bolt-segments impact {}))
  ([impact opts]
   (let [{:keys [height width max-offset passes branch-factor branch-length
                 width-shrink]}
         (merge strike-defaults opts)
         impact-v (vec3/v3 (double (:x impact)) (double (:y impact)) (double (:z impact)))
         top (v+ impact-v (vec3/v3 0.0 (double height) 0.0))
         trunk (refine-channel [top impact-v] max-offset passes)
         ;; Forks start at a trunk joint, keep heading roughly downward, and
         ;; get their own (shorter, thinner, dimmer) displaced channel.
         branches
         (into []
               (comp
                 (map-indexed vector)
                 (filter (fn [[idx _]] (and (pos? idx) (< (rand) branch-factor))))
                 (mapcat
                   (fn [[idx joint]]
                     (let [remaining (- (count trunk) idx)
                           span (* (double branch-length)
                                   (/ (double remaining) (count trunk))
                                   (double height))]
                       (when (> span 1.0)
                         (let [dir (random-rotate 35.0
                                                  (v* (vec3/v3 0.0 -1.0 0.0) span))
                               tip (v+ joint dir)]
                           (polyline->segments
                             (refine-channel [joint tip] (/ max-offset 2.0)
                                             (max 1 (dec passes)))
                             (* width width-shrink)
                             0.6)))))))
               trunk)]
     (into (polyline->segments trunk width 1.0) branches))))

(defn bolt-segments->ops
  "Render pre-generated bolt segments, scaling every segment's alpha by
  `alpha-scale` so the caller can drive the flash envelope."
  [segments alpha-scale]
  (let [scale (double alpha-scale)]
    (if-not (pos? scale)
      []
      (segment->quads (mapv #(update % :alpha * scale) segments)))))

;; ---------------------------------------------------------------------------
;; Public API: arc ops for railgun beam
;; ---------------------------------------------------------------------------

(defn railgun-arc-ops
  "Build arc render ops placed along a railgun beam.
  camera-pos: camera position for billboarding
  beam: {:keys [start end ttl max-ttl]} — start/end are V3
  style: {:keys [yaw pitch]} (from beam context)"
  [camera-pos beam style]
  (let [templates (ensure-templates)
        {:keys [start end ttl max-ttl]} beam
        ;; EntityRailgunFX clears its arc templates at age 30 ticks.
        alive? (> ttl (- max-ttl 30))
        num-templates (count templates)
        beam-length (v-length (v- end start))]
    (when (and alive? (seq templates) (> beam-length 1.0e-5))
      (let [direction (v-normalize (v- end start))
            candidate (if (< (Math/abs (.-y ^cn.li.mcmod.math.V3 direction)) 0.9)
                        vec3/unit-y
                        vec3/unit-x)
            right (v-normalize (v-cross candidate direction))
            up (v-normalize (v-cross direction right))
            placements (or (:arc-placements beam)
                           [{:distance 1.0 :theta 0.0 :radius 0.1}])]
        (mapcat (fn [idx {:keys [distance theta radius]}]
                  (let [radial (v+ (v* right (* radius (Math/sin theta)))
                                   (v* up (* radius (Math/cos theta))))
                        world-pos (v+ start
                                     (v+ (v* direction distance) radial))
                        ;; template is a list of segment-lists (main trunk +
                        ;; branches, per generate-arc-segments) — flatten to
                        ;; the flat segment list segment->quads expects.
                        template (nth templates (mod idx num-templates))]
                    (map (fn [quad]
                           (let [to-world
                                 (fn [^cn.li.mcmod.math.V3 p]
                                   (v+ world-pos
                                       (v+ (v* direction (.-x p))
                                           (v+ (v* right (.-y p))
                                               (v* up (.-z p))))))]
                             (-> quad
                                 (assoc :p0 (to-world (:p0 quad)))
                                 (assoc :p1 (to-world (:p1 quad)))
                                 (assoc :p2 (to-world (:p2 quad)))
                                 (assoc :p3 (to-world (:p3 quad))))))
                         (segment->quads (apply concat template)))))
                (range)
                placements)))))
