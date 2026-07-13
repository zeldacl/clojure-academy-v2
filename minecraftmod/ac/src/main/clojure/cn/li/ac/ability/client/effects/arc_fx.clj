(ns cn.li.ac.ability.client.effects.arc-fx
  "L-system lightning arc generation for beam effects.
  Ported from original AcademyCraft ArcFactory.java + SubArcHandler.java."
  (:require [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.util.math.vec3 :as vec3]))

;; ---------------------------------------------------------------------------
;; 3D vector helpers — generic ops delegate to util.math.vec3 (this used to be
;; a third private copy of the same {:x :y :z} algebra); yaw/pitch rotation and
;; the lerp/point/segment helpers below are arc_fx-specific, kept local.
;; ---------------------------------------------------------------------------

(def ^:private v+ vec3/v+)
(def ^:private v- vec3/v-)
(def ^:private v* vec3/v*)
(def ^:private v-cross vec3/vcross)
(def ^:private v-length vec3/vlen)
(def ^:private v-normalize vec3/vnorm)
(defn- v-lerp [a b t] {:x (+ (:x a) (* (- (:x b) (:x a)) t))
                       :y (+ (:y a) (* (- (:y b) (:y a)) t))
                       :z (+ (:z a) (* (- (:z b) (:z a)) t))})
(defn- v-rotate-yaw [a rad]
  (let [cos (Math/cos rad) sin (Math/sin rad)]
    {:x (+ (* (:x a) cos) (* (:z a) sin))
     :y (:y a)
     :z (- (* (:z a) cos) (* (:x a) sin))}))
(defn- v-rotate-pitch [a rad]
  (let [cos (Math/cos rad) sin (Math/sin rad)]
    {:x (:x a)
     :y (- (* (:y a) cos) (* (:z a) sin))
     :z (+ (* (:z a) cos) (* (:y a) sin))}))

(defn- random-rotate
  "Randomly rotate a vector within range degrees (matching original randomRotate)."
  [range-deg dir]
  (let [a-rad (/ (* range-deg (rand)) 180.0 Math/PI)
        pitched (v-rotate-pitch dir (- (* (rand) 2 a-rad) a-rad))]
    (v-rotate-yaw pitched (- (* (rand) 2 a-rad) a-rad))))

(defn- random-dir-orthogonal
  "Generate a random direction in the plane orthogonal to the given vector."
  [dir offset]
  (let [theta (* (rand) 2.0 Math/PI)
        sin-theta (Math/sin theta)
        cos-theta (Math/cos theta)
        ;; Find two orthogonal basis vectors
        candidate (if (< (Math/abs (double (:x dir))) 0.9)
                   {:x 1.0 :y 0.0 :z 0.0}
                   {:x 0.0 :y 1.0 :z 0.0})
        u (v-normalize (v-cross dir candidate))
        w (v-normalize (v-cross dir u))]
    (v+ (v* u (* offset sin-theta))
        (v* w (* offset cos-theta)))))

;; ---------------------------------------------------------------------------
;; Arc segment data types (as maps)
;; {:start {:x :y :z :width} :end {:x :y :z :width} :alpha}
;; ---------------------------------------------------------------------------

(defn- point [pt w] {:x (:x pt) :y (:y pt) :z (:z pt) :width w})
(defn- point-avg [p1 p2]
  {:x (* 0.5 (+ (:x p1) (:x p2)))
   :y (* 0.5 (+ (:y p1) (:y p2)))
   :z (* 0.5 (+ (:z p1) (:z p2)))
   :width (* 0.5 (+ (:width p1 0.1) (:width p2 0.1)))})

(defn- segment [start end alpha]
  {:start (if (:width start) start (point start 0.1))
   :end   (if (:width end)   end   (point end 0.1))
   :alpha alpha})

;; ---------------------------------------------------------------------------
;; L-system arc generation (matching original ArcFactory)
;; ---------------------------------------------------------------------------

(defn- handle-single-pass
  "Process one L-system pass: subdivide segments and possibly create branches.
  Returns {:main-arcs (updated segment lists) :branches (new branch lists)}."
  [segment-lists offset width-shrink alpha-shrink length-shrink branch-factor]
  (let [branches (atom [])
        result
        (mapv (fn [segments]
                (mapcat (fn [s]
                          (let [ave (point-avg (:start s) (:end s))
                                dir-vec (v- {:x (:x (:end s)) :y (:y (:end s)) :z (:z (:end s))}
                                            {:x (:x (:start s)) :y (:y (:start s)) :z (:z (:start s))})
                                displace-dir (random-dir-orthogonal
                                              (v-normalize dir-vec)
                                              (* (rand) offset))
                                ave-pt (point (v+ {:x (:x ave) :y (:y ave) :z (:z ave)}
                                                 displace-dir)
                                              (:width ave))
                                s1 (assoc s :end ave-pt)
                                s2 (segment ave-pt (:end s) (:alpha s))]
                            ;; Branching
                            (when (< (rand) branch-factor)
                              (let [dir (v* dir-vec length-shrink)
                                    rdir (random-rotate 10.0 dir)
                                    w2 (* (:width ave-pt) width-shrink)
                                    bp1 (point {:x (:x ave-pt) :y (:y ave-pt) :z (:z ave-pt)} w2)
                                    bp2 (point (v+ {:x (:x ave-pt) :y (:y ave-pt) :z (:z ave-pt)} rdir) w2)]
                                (swap! branches conj [(segment bp1 bp2 (* (:alpha s) alpha-shrink))])))
                            [s1 s2]))
                        segments))
              segment-lists)]
    {:main-arcs (vec result) :branches (vec @branches)}))

(defn- generate-arc-segments
  "Generate L-system arc segments for a single arc template.
  Returns list of segment-lists (main trunk + all branches)."
  [length width max-offset passes branch-factor width-shrink]
  (let [v0 {:x 0.0 :y 0.0 :z 0.0}
        v1 {:x length :y 0.0 :z 0.0}
        init [(segment (point v0 width) (point v1 width) 1.0)]
        alpha-shrink 0.9
        length-shrink 0.7]
    (loop [main-arcs [init]
           all-branches []
           offset max-offset
           flip false
           pass 0]
      (if (>= pass passes)
        (concat main-arcs all-branches)
        (let [source (if flip all-branches main-arcs)
              {:keys [main-arcs branches]}
              (handle-single-pass source offset width-shrink alpha-shrink length-shrink branch-factor)]
          (recur (if flip all-branches main-arcs)
                 (concat (if flip main-arcs all-branches) branches)
                 (/ offset 2.0)
                 (not flip)
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

(let [template-cache (atom nil)]
  (defn- ensure-templates
    "Generate arc templates on first call, cache and return."
    []
    (or @template-cache
        (let [templates
              (vec
               (for [_ (range default-num-templates)
                     :let [length (+ 1.0 (* (rand) 1.0))]] ;; spacing 1-2
                 (generate-arc-segments length
                   default-arc-width default-max-offset
                   default-passes default-branch-factor default-width-shrink)))]
          (reset! template-cache templates)
          templates)))

  (defn reset-arc-templates-for-test!
    "Clear cached templates (for testing)."
    []
    (reset! template-cache nil)
    nil))

;; ---------------------------------------------------------------------------
;; Arc rendering: convert segments to quads
;; ---------------------------------------------------------------------------

(defn- segment->quads
  "Convert a list of arc segments to render ops (textured quads)."
  [segments]
  (let [texture "my_mod:textures/effects/arc/line_segment.png"]
    (vec
     (for [[{:keys [start end alpha]} seg-idx] (map vector segments (range))
           :let [dir-vec (v- {:x (:x end) :y (:y end) :z (:z end)}
                             {:x (:x start) :y (:y start) :z (:z start)})
                 ;; Create perpendicular direction for quad width
                 perp (random-rotate 15.0 dir-vec)
                 perp (v-normalize (v-cross dir-vec perp))
                 perp (v* perp 1.0)
                 half-w1 (* 0.5 (:width start 0.15))
                 half-w2 (* 0.5 (:width end 0.15))
                 p0 (v+ {:x (:x start) :y (:y start) :z (:z start)} (v* perp half-w1))
                 p1 (v+ {:x (:x start) :y (:y start) :z (:z start)} (v* perp (- half-w1)))
                 p2 (v+ {:x (:x end) :y (:y end) :z (:z end)} (v* perp (- half-w2)))
                 p3 (v+ {:x (:x end) :y (:y end) :z (:z end)} (v* perp half-w2))
                 r 255 g 255 b 255
                 a (int (min 255 (* 255 alpha)))]]
       {:kind :quad
        :texture texture
        :p0 p0 :p1 p1 :p2 p2 :p3 p3
        :color {:r r :g g :b b :a a}}))))

;; ---------------------------------------------------------------------------
;; Public API: arc ops for railgun beam
;; ---------------------------------------------------------------------------

(defn railgun-arc-ops
  "Build arc render ops placed along a railgun beam.
  camera-pos: camera position for billboarding
  beam: {:keys [start end ttl max-ttl]}
  style: {:keys [yaw pitch]} (from beam context)"
  [camera-pos beam style]
  (let [templates (ensure-templates)
        {:keys [start end ttl max-ttl]} beam
        ;; Arcs alive until tick 30 (max-ttl - 20)
        alive? (> ttl (- max-ttl 20))
        num-templates (count templates)]
    (when (and alive? (seq templates))
      (let [direction (v-normalize (v- end start))
            beam-length (v-length (v- end start))
            spacing 1.5
            arc-count (max 1 (int (/ beam-length spacing)))]
        (mapcat (fn [idx]
                  (let [t (* (double idx) (/ beam-length (max 1 (dec arc-count))))
                        world-pos (v+ start (v* direction t))
                        template (nth templates (mod idx num-templates))]
                    (map (fn [quad] (-> quad
                                      (assoc :p0 (v+ (:p0 quad) world-pos))
                                      (assoc :p1 (v+ (:p1 quad) world-pos))
                                      (assoc :p2 (v+ (:p2 quad) world-pos))
                                      (assoc :p3 (v+ (:p3 quad) world-pos))))
                         (segment->quads template))))
                (range arc-count))))))
