(ns cn.li.ac.ability.client.render-util
  "Client-side rendering primitives for ability visual effects.

  Provides vector math, render-op constructors, and camera-basis helpers
  that are shared across all skill effect renderers."
  (:require [cn.li.ac.util.math.vec3 :as vec3]))

;; ---------------------------------------------------------------------------
;; Vector math
;; ---------------------------------------------------------------------------

(def v+ vec3/v+)
(def v- vec3/v-)
(def v* vec3/v*)
(def vlen vec3/vlen)
(def vnormalize vec3/vnorm)
(def vcross vec3/vcross)
(def vdot vec3/vdot)
(def rotate-around-axis vec3/rotate-around-axis)

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
  "minecraft:textures/entity/beacon_beam.png")

(declare beam-right-axis)

(defn billboard-beam-ops
  "Build the standard ability beam primitive: outer quad, inner/core quad, and
  optional center line. Widths are world-space half-widths around the beam axis."
  [cam-pos start end {:keys [texture width core-width core-ratio
                              outer-color inner-color line-color]}]
  (let [texture (or texture default-beam-texture)
        width (double (or width 0.0))
        core-width (double (or core-width (* width (double (or core-ratio 0.45)))))
        right (beam-right-axis start end cam-pos)
        outer-offset (v* right width)
        core-offset (v* right core-width)
        p0 (v+ start outer-offset)
        p1 (v- start outer-offset)
        p2 (v- end outer-offset)
        p3 (v+ end outer-offset)
        c0 (v+ start core-offset)
        c1 (v- start core-offset)
        c2 (v- end core-offset)
        c3 (v+ end core-offset)
        quads [(quad-op texture p0 p1 p2 p3 outer-color)
               (quad-op texture c0 c1 c2 c3 inner-color)]]
    (if line-color
      (conj quads (line-op start end line-color))
      quads)))

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
