(ns cn.li.ac.ability.client.render-util
  "Client-side rendering primitives for ability visual effects.

  Provides vector math, render-op constructors, and camera-basis helpers
  that are shared across all skill effect renderers.")

;; ---------------------------------------------------------------------------
;; Vector math
;; ---------------------------------------------------------------------------

(defn v+
  "Element-wise addition of two 3D vectors (maps with :x :y :z)."
  [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))})

(defn v-
  "Element-wise subtraction: a - b."
  [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn v*
  "Scalar multiplication of a 3D vector."
  [a scalar]
  {:x (* (double (:x a)) (double scalar))
   :y (* (double (:y a)) (double scalar))
   :z (* (double (:z a)) (double scalar))})

(defn vlen
  "Euclidean length of a 3D vector."
  ^double [v]
  (Math/sqrt (+ (* (double (:x v)) (double (:x v)))
                (* (double (:y v)) (double (:y v)))
                (* (double (:z v)) (double (:z v))))))

(defn vnormalize
  "Unit vector in the direction of v.  Returns {:x 1 :y 0 :z 0} for zero-length."
  [v]
  (let [len (max 1.0e-6 (vlen v))]
    (v* v (/ 1.0 len))))

(defn vcross
  "Cross product a × b."
  [a b]
  {:x (- (* (double (:y a)) (double (:z b))) (* (double (:z a)) (double (:y b))))
   :y (- (* (double (:z a)) (double (:x b))) (* (double (:x a)) (double (:z b))))
   :z (- (* (double (:x a)) (double (:y b))) (* (double (:y a)) (double (:x b))))})

(defn vdot
  "Dot product of two 3D vectors."
  ^double [a b]
  (+ (* (double (:x a)) (double (:x b)))
     (* (double (:y a)) (double (:y b)))
     (* (double (:z a)) (double (:z b)))))

(defn rotate-around-axis
  "Rodrigues' rotation: rotate `v` around `axis` by `degrees`."
  [v axis degrees]
  (let [axis-unit (vnormalize axis)
        theta (Math/toRadians (double degrees))
        cos-theta (Math/cos theta)
        sin-theta (Math/sin theta)
        term1 (v* v cos-theta)
        term2 (v* (vcross axis-unit v) sin-theta)
        term3 (v* axis-unit (* (vdot axis-unit v)
                               (- 1.0 cos-theta)))]
    (vnormalize (v+ (v+ term1 term2) term3))))

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

;; ---------------------------------------------------------------------------
;; Camera-relative basis helpers
;; ---------------------------------------------------------------------------

(defn beam-right-axis
  "Compute the right axis for a billboard beam between `start` and `end`,
  perpendicular to both the beam direction and the camera view direction."
  [start end cam-pos]
  (let [dir (vnormalize (v- end start))
        mid (v* (v+ start end) 0.5)
        to-cam (vnormalize (v- cam-pos mid))
        raw (vcross dir to-cam)]
    (if (> (vlen raw) 1.0e-5)
      (vnormalize raw)
      {:x 1.0 :y 0.0 :z 0.0})))

(defn camera-facing-right-axis
  "Right axis for a camera-facing billboard at `center`."
  [center cam-pos]
  (let [to-cam (vnormalize (v- cam-pos center))
        up {:x 0.0 :y 1.0 :z 0.0}
        raw (vcross up to-cam)]
    (if (> (vlen raw) 1.0e-5)
      (vnormalize raw)
      {:x 1.0 :y 0.0 :z 0.0})))

(defn billboard-up-axis
  "Up axis for a billboard given `center`, `cam-pos`, and already-computed `right`."
  [center cam-pos right]
  (let [to-cam (vnormalize (v- cam-pos center))
        raw (vcross to-cam right)]
    (if (> (vlen raw) 1.0e-5)
      (vnormalize raw)
      {:x 0.0 :y 1.0 :z 0.0})))
