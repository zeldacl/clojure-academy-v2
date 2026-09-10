(ns cn.li.platform.neutral.arc-geometry
  "Neutral zigzag lightning geometry for V4 `:arc` scene ops.

   Ported from main's `arc-patterns/generate-zigzag-segments` +
   `render-util/zigzag-arc-ops` (AcademyCraft EntityArc / ArcPatterns), but
   kept Minecraft-free: only `cn.li.mcmod.math.V3` and plain maps so
   `vfx-render-plan` can expand draw batches."
  (:import [cn.li.mcmod.math V3]))

(def ^:private default-texture "academy:textures/effects/arc/line_segment.png")

;; LambdaLib2 ViewOptimize.fix offsets in the arc's local
;; [forward, up, right] frame — same numbers main's arc-beam template used.
(def first-person-view-offset [-0.05 -0.25 0.2])
(def third-person-view-offset [0.15 -0.8 0.23])

(def ^:private patterns
  {:weak {:segments 24 :amplitude 0.12 :width 0.1}
   :strong {:segments 20 :amplitude 0.07 :width 0.3}
   :aoe {:segments 20 :amplitude 0.06 :width 0.13}})

(defn- pattern-of [key]
  (get patterns (keyword key) (:weak patterns)))

(defn- v+ ^V3 [^V3 a ^V3 b] (V3/add a b))
(defn- v- ^V3 [^V3 a ^V3 b] (V3/sub a b))
(defn- v* ^V3 [^V3 a ^double s] (V3/scale a s))
(defn- vcross ^V3 [^V3 a ^V3 b] (V3/cross a b))
(defn- vnorm ^V3 [^V3 a] (V3/normalize a))
(defn- vlen ^double [^V3 a] (V3/length a))

(defn- as-v3
  ^V3 [value]
  (cond
    (instance? V3 value) value
    (map? value)
    (V3. (double (or (:x value) (get value "x") 0.0))
         (double (or (:y value) (get value "y") 0.0))
         (double (or (:z value) (get value "z") 0.0)))
    (sequential? value)
    (let [[x y z] (concat value [0.0 0.0 0.0])]
      (V3. (double x) (double y) (double z)))
    :else (V3. 0.0 0.0 0.0)))

(defn- v3->map [^V3 v]
  {:x (.-x v) :y (.-y v) :z (.-z v)})

(defn local-frame-offset
  "Resolve [forward up right] against the bolt's own axes (main ViewOptimize)."
  ^V3 [start end [forward-o up-o right-o]]
  (let [start-v3 (as-v3 start)
        end-v3 (as-v3 end)
        forward (vnorm (v- end-v3 start-v3))
        right-raw (vcross forward (V3. 0.0 1.0 0.0))
        right (if (> (vlen right-raw) 1.0e-5)
                (vnorm right-raw)
                (V3. 1.0 0.0 0.0))
        up (vnorm (vcross right forward))]
    (v+ (v+ (v* forward (double forward-o))
            (v* up (double up-o)))
        (v* right (double right-o)))))

(defn select-hand-origin-offset
  "Pick own (first-person caster) vs other (F5 / remote) ViewOptimize offset.
   `view-ctx` is presentation-world's hand-center-pos map."
  [view-ctx {:keys [source-player-id]}]
  (let [own? (and (:first-person? view-ctx true)
                  (:player-uuid view-ctx)
                  source-player-id
                  (= (str (:player-uuid view-ctx)) (str source-player-id)))]
    (if own? first-person-view-offset third-person-view-offset)))

(defn apply-hand-origin
  "Translate geometry :start/:end by the viewer-dependent hand offset when
   `:hand-origin?` is set. Pure; no-op without view context or the flag."
  [geometry view-ctx]
  (if-not (and view-ctx (true? (:hand-origin? geometry)))
    geometry
    (let [offset-triple (select-hand-origin-offset view-ctx geometry)
          fix (local-frame-offset (:start geometry) (:end geometry) offset-triple)
          fix-end? (not (false? (:fix-end? geometry)))]
      (-> geometry
          (update :start (fn [p] (v3->map (v+ (as-v3 p) fix))))
          (cond-> fix-end? (update :end (fn [p] (v3->map (v+ (as-v3 p) fix)))))))))

(defn with-hand-origin-view
  "Apply hand-origin to a draw-batch op payload (legacy-op / VfxBatch payload)."
  [op view-ctx]
  (if-not (and (map? op) view-ctx (= :arc (get-in op [:geometry :kind])))
    op
    (update op :geometry apply-hand-origin view-ctx)))

(defn generate-zigzag-segments
  "Deterministic midpoint-displacement path from start→end."
  [^V3 start ^V3 end {:keys [segments amplitude seed]
                      :or {segments 8 amplitude 0.15 seed 42}}]
  (let [end-start (v- end start)
        dir (vnorm end-start)
        len (vlen end-start)
        up (if (< (Math/abs (.-y dir)) 0.99) (V3. 0.0 1.0 0.0) (V3. 1.0 0.0 0.0))
        perp (vnorm (vcross dir up))
        perp2 (vnorm (vcross dir perp))
        passes (max 1 (int (Math/ceil (/ (Math/log (max 2.0 (double segments)))
                                         (Math/log 2.0)))))
        rng (java.util.Random. (long seed))
        displace (fn [magnitude]
                   (let [theta (* 2.0 Math/PI (.nextDouble rng))
                         r (* (double magnitude) (.nextDouble rng))]
                     (v+ (v* perp (* r (Math/sin theta)))
                         (v* perp2 (* r (Math/cos theta))))))]
    (loop [pass 0
           pts [[0.0 (V3. 0.0 0.0 0.0)] [1.0 (V3. 0.0 0.0 0.0)]]]
      (if (>= pass passes)
        (mapv (fn [[t off]]
                (v+ (v+ start (v* end-start (double t))) off))
              pts)
        (let [magnitude (* (double amplitude) len (Math/pow 0.5 pass))]
          (recur (inc pass)
                 (into [(first pts)]
                       (mapcat (fn [[[t0 off0] [t1 off1]]]
                                 (let [t-mid (* 0.5 (+ (double t0) (double t1)))
                                       off-mid (v+ (v* (v+ off0 off1) 0.5)
                                                   (displace magnitude))]
                                   [[t-mid off-mid] [t1 off1]]))
                               (partition 2 1 pts)))))))))

(defn- life-fade-alpha
  "Match main arc-patterns/life-fade-alpha: full until late life, then drop."
  ^double [life-ratio]
  (let [lr (max 0.0 (min 1.0 (double life-ratio)))]
    (if (< lr 0.7)
      255.0
      (* 255.0 (/ (- 1.0 lr) 0.3)))))

(defn arc-quad-ops
  "Expand one `:arc` geometry into textured segment quads."
  [geometry material color]
  (let [start (as-v3 (:start geometry))
        end (as-v3 (:end geometry))
        pat (pattern-of (or (:pattern geometry) :weak))
        seed (long (or (:seed geometry) 0))
        life-ratio (double (or (:life-ratio geometry) 0.0))
        alpha-scale (double (or (:alpha material) 1.0))
        base-alpha (* alpha-scale (life-fade-alpha life-ratio))
        a (int (max 0 (min 255 (long base-alpha))))
        tint (or color [255 255 255 a])
        tint (if (sequential? tint)
               (let [[r g b _a] (concat tint [255 255 255 255])]
                 [r g b a])
               tint)
        width (double (or (:width geometry) (:width pat) 0.1))
        texture (or (:texture material) (:texture geometry) default-texture)
        vertices (generate-zigzag-segments start end
                                           {:segments (:segments pat)
                                            :amplitude (:amplitude pat)
                                            :seed seed})
        segment-count (dec (count vertices))]
    (if (< segment-count 1)
      []
      (let [forward (vnorm (v- end start))
            normal-raw (vcross forward (V3. 0.0 1.0 0.0))
            normal (if (> (vlen normal-raw) 1.0e-5)
                     (vnorm normal-raw)
                     (V3. 1.0 0.0 0.0))
            laterals (loop [i 0 acc [] prev (V3. 1.0 0.0 0.0)]
                       (if (>= i segment-count)
                         acc
                         (let [v0 (nth vertices i)
                               v1 (nth vertices (inc i))
                               dir-vec (v- v1 v0)
                               raw (vcross dir-vec normal)
                               lateral (if (> (vlen raw) 1.0e-5)
                                         (vnorm raw)
                                         prev)]
                           (recur (inc i) (conj acc lateral) lateral))))]
        (mapv (fn [i]
                (let [seg-start (nth vertices i)
                      seg-end (nth vertices (inc i))
                      right-start (nth laterals (if (zero? i) 0 (dec i)))
                      right-end (nth laterals i)
                      outer-s (v* right-start width)
                      outer-e (v* right-end width)]
                  {:kind :quad
                   :p0 (v+ seg-start outer-s)
                   :p1 (v- seg-start outer-s)
                   :p2 (v- seg-end outer-e)
                   :p3 (v+ seg-end outer-e)
                   :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
                   :texture texture
                   :color tint}))
              (range segment-count))))))
