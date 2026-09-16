(ns cn.li.platform.neutral.vfx-render-plan
  "Translate the Minecraft-free VFX draw ABI into a version geometry plan.

   VFX Core owns evaluated, typed-neutral draw operations.  The existing
   mc-* geometry modules remain responsible for Minecraft buffer state, so
   this adapter only expands line/beam/ring/quad/particle geometry into their
   long-lived {:ops [...]} plan shape.  It imports no Minecraft class."
  (:require [clojure.string :as str]
            [cn.li.platform.neutral.arc-geometry :as arc-geometry])
  (:import [cn.li.mcmod.math V3]
           [cn.li.mcmod.runtime.vfx ParticleColumns]))

(def ^:private default-color [255 255 255 255])
(def ^:private default-texture "minecraft:textures/misc/white.png")

(defn- number-or [value fallback]
  (if (number? value) (double value) (double fallback)))

(defn- v3-from
  ^V3 [value]
  (cond
    (instance? V3 value) value
    (and (map? value) (sequential? (:vec3 value)))
    (let [[x y z] (concat (:vec3 value) [0.0 0.0 0.0])]
      (V3. (number-or x 0.0) (number-or y 0.0) (number-or z 0.0)))
    (map? value)
    (V3. (number-or (or (:x value) (get value "x")) 0.0)
         (number-or (or (:y value) (get value "y")) 0.0)
         (number-or (or (:z value) (get value "z")) 0.0))
    (sequential? value)
    (let [[x y z] (concat value [0.0 0.0 0.0])]
      (V3. (number-or x 0.0) (number-or y 0.0) (number-or z 0.0)))
    :else
    (V3. 0.0 0.0 0.0)))

(defn- material-color [material]
  (let [color (or (:color material) (get material "color") default-color)
        alpha (:alpha material)]
    (if (and (number? alpha) (map? color))
      (assoc color :a (* (double (or (:a color) 1.0)) (double alpha)))
      color)))

(defn- line-op [p1 p2 color]
  {:kind :line :p1 (v3-from p1) :p2 (v3-from p2) :color color})

(defn- ring-ops [{:keys [center radius segments]} color]
  (let [center (v3-from center)
        radius (max 0.0 (number-or radius 0.0))
        segments (long (max 3 (min 256 (number-or segments 16))))]
    (mapv (fn [i]
            (let [a0 (* 2.0 Math/PI (/ i segments))
                  a1 (* 2.0 Math/PI (/ (inc i) segments))]
              (line-op
               (V3. (+ (.-x center) (* radius (Math/cos a0)))
                    (.-y center)
                    (+ (.-z center) (* radius (Math/sin a0))))
               (V3. (+ (.-x center) (* radius (Math/cos a1)))
                    (.-y center)
                    (+ (.-z center) (* radius (Math/sin a1))))
               color)))
          (range segments))))

(defn- target-box-ops [{:keys [center width height]} color]
  (let [center (v3-from center)
        width (max 0.001 (number-or width 0.5))
        height (max 0.001 (number-or height 0.5))
        len (* 0.2 width)
        rots [0.0 -90.0 -180.0 -270.0 0.0 -90.0 -180.0 -270.0]
        axis (fn [theta]
               (let [r (Math/toRadians theta)
                     c (Math/cos r)
                     s (Math/sin r)]
                 {:x1 c :z1 (- s) :x2 s :z2 c}))
        ox (- (.-x center) (* 0.5 width))
        oy (.-y center)
        oz (- (.-z center) (* 0.5 width))
        corners [[0 0 0] [1 0 0] [1 0 1] [0 0 1]
                 [0 1 0] [1 1 0] [1 1 1] [0 1 1]]]
    (vec
     (mapcat (fn [[cx cy cz] theta]
               (let [x (+ ox (* cx width))
                     y (+ oy (* cy height))
                     z (+ oz (* cz width))
                     rev (< cy 0.5)
                     vert (if rev len (- len))
                     {ax1 :x1 az1 :z1 ax2 :x2 az2 :z2} (axis theta)
                     translucent-line (fn [p1 p2]
                                        (assoc (line-op p1 p2 color) :translucent? true))]
                 [(translucent-line (V3. x y z) (V3. x (+ y vert) z))
                  (translucent-line (V3. x y z)
                                     (V3. (+ x (* ax1 len)) y (+ z (* az1 len))))
                  (translucent-line (V3. x y z)
                                     (V3. (+ x (* ax2 len)) y (+ z (* az2 len))))]))
             corners
             rots))))

(defn- line-ops [geometry color]
  (let [p1 (or (:p1 geometry) (:start geometry) (:from geometry))
        p2 (or (:p2 geometry) (:end geometry) (:to geometry))]
    (if (and p1 p2) [(line-op p1 p2 color)] [])))

(def ^:private beam-glow-texture "academy:textures/effects/glow_line.png")
(def ^:private beam-tube-texture "academy:textures/effects/solid.png")
(def ^:private tube-segments 12)
(def ^:private tube-head-segments 4)

(defn- scale-color-alpha [color alpha]
  (cond
    (and (sequential? color) (<= 4 (count color)))
    (let [[r g b a] color]
      [r g b (* (double a) (double alpha))])
    (map? color)
    (assoc color :a (* (double (or (:a color) 1.0)) (double alpha)))
    :else color))

(defn- beam-quad [^V3 start ^V3 end ^V3 axis radius texture color]
  (let [offset (V3/scale axis radius)
        start-left (V3/sub start offset)
        start-right (V3/add start offset)
        end-right (V3/add end offset)
        end-left (V3/sub end offset)]
    {:kind :quad
     :p0 start-left :p1 start-right :p2 end-right :p3 end-left
     :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
     :texture texture :color color}))

(defn- glow-board
  [^V3 start ^V3 end ^V3 axis width texture color]
  (assoc (beam-quad start end axis (* 0.5 (double width)) texture color)
         :additive? true
         :no-depth-write? true))

(defn- beam-glow-ops
  "Expand the three-board glow used by the main railgun renderer.

  The V4 beam ABI keeps this as one declarative layer because the placement of
  the boards is renderer geometry, not skill logic. Each board covers one
  segment of the ray, so blend-in/blend-out textures remain end caps instead
  of being stretched over the entire beam. Two crossed boards preserve the
  visibility of the glow from arbitrary camera angles."
  [^V3 start ^V3 end ^V3 right ^V3 up layer color]
  (let [delta (V3/sub end start)
        length (V3/length delta)]
    (if (<= length 1.0e-5)
      []
      (let [direction (V3/scale delta (/ 1.0 length))
            width (max 0.001 (number-or (or (:width layer) (get layer "width")) 1.1))
            textures (or (:textures layer) (get layer "textures") {})
            blend-in (or (:blend-in textures) (get textures "blend-in"))
            tile (or (:tile textures) (get textures "tile"))
            blend-out (or (:blend-out textures) (get textures "blend-out"))
            gs (V3/sub start (V3/scale direction 0.3))
            ge (V3/add end (V3/scale direction 0.3))
            span (V3/length (V3/sub ge gs))
            cap (min width (* 0.5 span))
            mid1 (V3/add gs (V3/scale direction cap))
            mid2 (V3/sub ge (V3/scale direction cap))
            boards (fn [axis]
                     [(glow-board gs mid1 axis width blend-in color)
                      (glow-board mid1 mid2 axis width tile color)
                      (glow-board mid2 ge axis width blend-out color)])]
        (vec (concat (boards right) (boards up)))))))

(defn- trajectory-start
  "Match VecAccel's first-person start offset from the main renderer.

   The server supplies the rendered eye position as `origin`; the remaining
   offset is derived from look direction so the ribbon begins at the same
   weapon-relative point in every frame."
  ^V3 [^V3 origin ^V3 look-dir lateral-offset vertical-offset forward-offset]
  (let [lx (.-x look-dir) ly (.-y look-dir) lz (.-z look-dir)
        horizontal (Math/sqrt (+ (* lx lx) (* lz lz)))
        safe-horizontal (max 1.0e-8 horizontal)
        lateral (number-or lateral-offset 0.0)
        vertical (number-or vertical-offset 0.0)
        forward (number-or forward-offset 0.0)]
    (V3/add origin
            (V3. (- (* -1.0 lateral (/ lz safe-horizontal)) (* lx forward))
                 (- vertical (* ly forward))
                 (+ (* lateral (/ lx safe-horizontal)) (* -1.0 lz forward))))))

(defn- integrate-trajectory
  "Integrate the same drag/gravity path as main VecAccel."
  [^V3 init-vel segments dt drag gravity]
  (let [segments (long (max 2 (min 256 (number-or segments 100))))
        dt (number-or dt 0.02) drag (number-or drag 0.98)
        gravity (number-or gravity 1.9)]
    (loop [idx 0 ^V3 pos (V3. 0.0 0.0 0.0) ^V3 vel init-vel
           positions [(V3. 0.0 0.0 0.0)]]
      (if (>= idx (dec segments))
        positions
        (let [vel2 (V3. (* (.-x vel) drag) (* (.-y vel) drag) (* (.-z vel) drag))
              pos2 (V3. (+ (.-x pos) (* (.-x vel2) dt))
                        (+ (.-y pos) (* (.-y vel2) dt))
                        (+ (.-z pos) (* (.-z vel2) dt)))
              vel3 (V3. (.-x vel2) (- (.-y vel2) (* dt gravity)) (.-z vel2))]
          (recur (inc idx) pos2 vel3 (conj positions pos2)))))))

(defn- trajectory-color
  [style can-perform? alpha]
  (let [style (or style {})
        color (or (if can-perform?
                    (or (:ready-color style) (get style "ready-color"))
                    (or (:blocked-color style) (get style "blocked-color")))
                   (if can-perform? [255 255 255] [255 51 51]))
        [r g b] (concat color [255 255 255])]
    [(number-or r 255.0) (number-or g 255.0) (number-or b 255.0) alpha]))

(defn- trajectory-ops
  [geometry material view-ctx]
  (if-not (get-in view-ctx [:hand-center-pos :first-person?] true)
    []
    (let [origin (v3-from (or (:camera-pos view-ctx) (:origin geometry)))
        look-dir (v3-from (:look-dir geometry)) init-vel (v3-from (:init-vel geometry))
        start (trajectory-start origin look-dir (:lateral-offset geometry)
                                (:vertical-offset geometry) (:forward-offset geometry))
        positions (integrate-trajectory init-vel (:segments geometry) (:dt geometry)
                                        (:drag geometry) (:gravity geometry))
        style (:style geometry) can-perform? (boolean (:can-perform? geometry))
        height (max 0.001 (number-or (or (:height style) (get style "height")
                                         (:width geometry)) 0.02))
        texture (or (:texture material) beam-glow-texture)]
    (mapv (fn [idx]
            (let [^V3 prev (V3/add start (nth positions (dec idx)))
                  ^V3 pos (V3/add start (nth positions idx))
                  alpha (int (* 255.0 (max 0.0 (- 0.7 (* (double idx) 0.021)))))
                  color (trajectory-color style can-perform? alpha)]
              {:kind :quad
               :p0 (V3. (.-x prev) (+ (.-y prev) height) (.-z prev))
               :p1 (V3. (.-x prev) (- (.-y prev) height) (.-z prev))
               :p2 (V3. (.-x pos) (- (.-y pos) height) (.-z pos))
               :p3 (V3. (.-x pos) (+ (.-y pos) height) (.-z pos))
               :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
               :texture texture :color color :additive? true :no-depth-write? true}))
          (range 1 (dec (count positions)))))))
(defn- tube-profile
  "Sample the short paraboloid noses and cylindrical body used by main's
   RendererRayCylinder. Keeping the profile in the neutral plan makes the V4
   :tube layer a real volume instead of a camera-angle-dependent strip."
  [length radius head-fix]
  (let [nose (* radius head-fix)
        nose-points (for [i (range (inc tube-head-segments))
                          :let [u (/ (double i) tube-head-segments)]]
                      [(* nose u) (* radius (Math/sqrt u))])
        tail-points (for [i (range 1 (inc tube-head-segments))
                          :let [u (/ (double i) tube-head-segments)]]
                      [(+ length (* nose u))
                       (* radius (Math/sqrt (- 1.0 u)))])]
    (if (<= length nose)
      (concat nose-points tail-points)
      (concat nose-points [[length radius]] tail-points))))

(defn- tube-quad [p0 p1 p2 p3 color]
  {:kind :quad
   :p0 p0 :p1 p1 :p2 p2 :p3 p3
   :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
   ;; RendererRayCylinder uses an untextured vertex-colour material. The
   ;; neutral backend needs a texture-backed RenderType, so use the opaque
   ;; white sprite rather than railgun's tapered glow sprite, which would
   ;; make the tube appear hollow or disappear.
   :texture beam-tube-texture :color color})

(defn- tube-ops
  "Expand one beam tube into a 12-sided surface with tapered end caps.

   This is the V4 equivalent of main's inner/outer ray cylinders. It is
   intentionally generated here, after the skill has supplied only radius and
   colour, so every skill receives the same solid-beam silhouette."
  [^V3 start ^V3 end radius color]
  (let [delta (V3/sub end start)
        length (V3/length delta)
        radius (double radius)]
    (if (or (<= length 1.0e-5) (<= radius 1.0e-5))
      []
      (let [direction (V3/scale delta (/ 1.0 length))
            reference (if (> (Math/abs (.-y direction)) 0.9)
                        (V3. 1.0 0.0 0.0)
                        (V3. 0.0 1.0 0.0))
            right (V3/normalize (V3/cross reference direction))
            up (V3/normalize (V3/cross direction right))
            dtheta (/ (* 2.0 Math/PI) tube-segments)
            ring (vec (for [i (range (inc tube-segments))
                            :let [angle (* (double i) dtheta)]]
                        (V3/add (V3/scale right (Math/cos angle))
                                (V3/scale up (Math/sin angle)))))
            at (fn [distance width axis]
                (V3/add (V3/add start (V3/scale direction (double distance)))
                        (V3/scale axis (double width))))
            profile (tube-profile length radius 1.0)]
        (vec
          (for [[[d0 w0] [d1 w1]] (partition 2 1 profile)
                i (range tube-segments)
                :let [axis0 (nth ring i)
                      axis1 (nth ring (inc i))]]
            (tube-quad (at d0 w0 axis0)
                       (at d0 w0 axis1)
                       (at d1 w1 axis1)
                       (at d1 w1 axis0)
                       color)))))))

(defn- beam-layer-ops [^V3 start ^V3 end ^V3 right ^V3 up layer material]
  (let [shape (or (:shape layer) (get layer "shape") :tube)
        width (max 0.001 (number-or (or (:width layer) (get layer "width")
                                        (:radius layer) (get layer "radius")
                                        0.08)
                                     0.08))
        color (scale-color-alpha (or (:color layer) (get layer "color")
                                    (material-color material))
                                 (number-or (or (:alpha material)
                                                (get material "alpha") 1.0)
                                             1.0))
        texture (or (:texture layer) (get layer "texture")
                    (:texture material) beam-glow-texture)]
    (case shape
      :line [(line-op start end color)]
      :glow (beam-glow-ops start end right up layer color)
      :tube (tube-ops start end width color)
      [(assoc (beam-quad start end right width texture color)
              :additive? true :no-depth-write? true)
       (assoc (beam-quad start end up width texture color)
              :additive? true :no-depth-write? true)])))

(defn- beam-ops [geometry material]
  (let [start (v3-from (or (:start geometry) (:from geometry)))
        end (v3-from (or (:end geometry) (:to geometry)))
        direction (V3/normalize (V3/sub end start))
        reference (if (> (Math/abs (.-y direction)) 0.9)
                    (V3. 1.0 0.0 0.0)
                    (V3. 0.0 1.0 0.0))
        right (V3/normalize (V3/cross direction reference))
        up (V3/normalize (V3/cross right direction))
        layers (or (:layers material) (get material "layers")
                   [material])]
    (into [] (mapcat #(beam-layer-ops start end right up % material) layers))))

(defn- range-value [value fallback]
  (cond
    (and (map? value) (number? (:min value)) (number? (:max value)))
    [(double (:min value)) (double (:max value))]
    (and (sequential? value) (= 2 (count value)) (every? number? value))
    (mapv double value)
    :else [fallback fallback]))

(defn- fan-direction [yaw-degrees pitch-degrees]
  (let [yaw (Math/toRadians (double yaw-degrees))
        pitch (Math/toRadians (double pitch-degrees))
        cp (Math/cos pitch)]
    (V3. (* -1.0 (Math/sin yaw) cp)
         (Math/sin pitch)
         (* (Math/cos yaw) cp))))

(defn- ray-fan-ops
  "Expand the main RayBarrage fan into deterministic textured rays.

   Main chooses one yaw half-angle in [50,60], then uses that same random
   angle for yaw +/- angle and pitch +/- angle/2. The V4 fan keeps the
   server-provided count/seed while reproducing that geometry client-side."
  [geometry material]
  (let [origin (v3-from (:origin geometry))
        base (V3/normalize (v3-from (:direction geometry)))
        yaw-center (Math/toDegrees (Math/atan2 (- (.-x base)) (.-z base)))
        pitch-center (Math/toDegrees (Math/asin (max -1.0 (min 1.0 (.-y base)))))
        [yaw-min yaw-max] (range-value (:yaw-range-degrees geometry) 0.0)
        [_pitch-min pitch-max-raw] (range-value (:pitch-range-degrees geometry) 0.0)
        pitch-max (Math/abs (double (number-or pitch-max-raw 0.0)))
        count (long (max 0 (min 128 (number-or (:count geometry) 0))))
        length (max 0.0 (number-or (:length geometry) 0.0))
        age (max 0.0 (number-or (:age geometry) 0.0))
        life (max 1.0 (number-or (:life-ticks geometry) 1.0))
        grow (max 0.0 (number-or (:grow-ticks geometry) 0.0))
        fade-in (max 0.0 (number-or (or (:fade-in-ticks material) (get material "fade-in-ticks")) 0.0))
        fade-out (max 0.0 (number-or (or (:fade-out-ticks material) (get material "fade-out-ticks")) 0.0))
        grow-ratio (if (pos? grow) (min 1.0 (/ age grow)) 1.0)
        alpha (* (if (pos? fade-in) (min 1.0 (/ age fade-in)) 1.0)
                 (if (pos? fade-out) (min 1.0 (/ (- life age) fade-out)) 1.0))
        rng (java.util.Random. (long (or (:seed geometry) 0)))
        fan-material (assoc material :alpha (max 0.0 (min 1.0 alpha)))]
    (vec
      (mapcat
        (fn [_]
          (let [half-angle (+ yaw-min (* (.nextDouble rng) (- yaw-max yaw-min)))
                yaw-offset (- (* 2.0 half-angle (.nextDouble rng)) half-angle)
                pitch-half (* 0.5 half-angle (if (pos? pitch-max) (/ pitch-max 30.0) 1.0))
                pitch-offset (- (* 2.0 pitch-half (.nextDouble rng)) pitch-half)
                dir (fan-direction (+ yaw-center yaw-offset)
                                   (+ pitch-center pitch-offset))
                end (V3/add origin (V3/scale dir (* length grow-ratio)))]
            (beam-ops {:start origin :end end} fan-material)))
        (range count)))))


(defn- quad-ops [geometry color material]
  (let [corners (map #(get geometry %) [:p0 :p1 :p2 :p3])]
    (if (every? some? corners)
      [{:kind :quad
        :p0 (v3-from (nth corners 0))
        :p1 (v3-from (nth corners 1))
        :p2 (v3-from (nth corners 2))
        :p3 (v3-from (nth corners 3))
        :u0 (float (number-or (:u0 geometry) 0.0))
        :u1 (float (number-or (:u1 geometry) 1.0))
        :v0 (float (number-or (:v0 geometry) 0.0))
        :v1 (float (number-or (:v1 geometry) 1.0))
        :texture (or (:texture geometry) (:texture material) default-texture)
        :color color}]
      [])))

(defn- range-pair [value fallback]
  (cond
    (map? value)
    (let [lo (number-or (or (:min value) (get value "min")) fallback)
          hi (number-or (or (:max value) (get value "max")) lo)]
      [(min lo hi) (max lo hi)])

    (and (sequential? value) (seq value))
    (let [lo (number-or (first value) fallback)
          hi (number-or (second value) lo)]
      [(min lo hi) (max lo hi)])

    :else [fallback fallback]))

(defn- random-between [^java.util.Random rng [lo hi]]
  (+ lo (* (.nextDouble rng) (- hi lo))))

(defn- particle-trail-billboard [center cam-pos half color texture]
  (let [center (v3-from center)
        cam (v3-from cam-pos)
        to-camera (V3/sub cam center)
        distance (V3/length to-camera)
        forward (if (> distance 1.0e-6)
                  (V3/scale to-camera (/ 1.0 distance))
                  (V3. 0.0 0.0 1.0))
        reference (if (> (Math/abs (.-y forward)) 0.9)
                   (V3. 1.0 0.0 0.0)
                   (V3. 0.0 1.0 0.0))
        right (V3/normalize (V3/cross forward reference))
        up (V3/normalize (V3/cross right forward))
        side (V3/scale right half)
        lift (V3/scale up half)]
    {:kind :quad
     :p0 (V3/sub (V3/sub center side) lift)
     :p1 (V3/add (V3/sub center side) lift)
     :p2 (V3/add (V3/add center side) lift)
     :p3 (V3/sub (V3/add center side) lift)
     :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
     :texture texture :color color}))

(defn- particle-trail-ops [geometry material view-ctx]
  "Reproduce main's shift-teleport TPParticleFactory burst.

  The V4 scene supplies immutable burst inputs and age; this adapter expands
  them into camera-facing quads and applies main's drift/fade curve."
  (let [start (v3-from (:start geometry))
        end (v3-from (:end geometry))
        delta (V3/sub end start)
        distance (V3/length delta)
        direction (if (> distance 1.0e-6)
                    (V3/scale delta (/ 1.0 distance))
                    (V3. 0.0 0.0 0.0))
        limit (long (max 0 (min 256 (number-or (:count-limit geometry) 128))))
        spacing (range-pair (:spacing geometry) 1.0)
        radius (range-pair (:radius geometry) 0.15)
        alpha (range-pair (:alpha geometry) 180.0)
        velocity (:velocity geometry)
        velocity-range (fn [axis fallback]
                         (range-pair (or (get velocity axis)
                                         (get velocity (name axis))) fallback))
        vx (velocity-range :x 0.0)
        vy (velocity-range :y 0.0)
        vz (velocity-range :z 0.0)
        life (long (max 0 (number-or (:life-ticks geometry) 20)))
        fade-in (long (max 0 (number-or (:fade-in geometry) 5)))
        fade-out (long (max 1 (number-or (:fade-out geometry) 20)))
        age (long (max 0 (number-or (:age geometry) 0.0)))
        texture (or (:texture geometry) (:texture material) default-texture)
        cam-pos (or (:camera-pos view-ctx) [0.0 0.0 0.0])
        rng (java.util.Random. (long (hash [:particle-trail (or (:seed geometry) 0)])))]
    (if (or (zero? limit) (<= distance 1.0e-6))
      []
      (loop [along 1.0
             particles []]
        (if (or (>= (count particles) limit) (> along distance))
          particles
          (let [point (V3/add start (V3/scale direction along))
                alpha0 (random-between rng alpha)
                particle-age (double age)
                fade-factor (cond
                              (and (pos? fade-in) (< age fade-in)) (/ particle-age fade-in)
                              (<= age life) 1.0
                              :else (max 0.0 (- 1.0 (/ (- particle-age life) fade-out))))
                color {:r 255 :g 255 :b 255
                       :a (* alpha0 (max 0.0 (min 1.0 fade-factor)))}
                drift (V3. (* (random-between rng vx) particle-age)
                           (* (random-between rng vy) particle-age)
                           (* (random-between rng vz) particle-age))
                center (V3/add point drift)
                size (random-between rng radius)
                next-step (random-between rng spacing)]
            (recur (+ along next-step)
                   (conj particles
                         (particle-trail-billboard center cam-pos
                                                    (* 0.5 size) color texture)))))))))
(defn- animated-texture [particle]
  (let [texture (:texture particle)
        frame-count (long (max 1.0 (number-or (:frame-count particle) 1)))
        frame-duration-ms (long (max 1.0 (number-or (:frame-duration-ms particle) 50)))
        age (max 0.0 (number-or (:age particle) 0.0))
        frame (long (mod (Math/floor (/ (* age 50.0) frame-duration-ms)) frame-count))]
    (if (and (string? texture) (str/includes? texture "%d"))
      (str/replace texture "%d" (str frame))
      (or texture default-texture))))

(defn- view-basis [view-ctx]
  (if (and (map? view-ctx)
           (number? (:player-yaw-rad view-ctx))
           (number? (:player-pitch-rad view-ctx)))
    (let [yaw (double (:player-yaw-rad view-ctx))
          pitch (double (:player-pitch-rad view-ctx))
          cos-pitch (Math/cos pitch)
          forward (V3. (* (- (Math/sin yaw)) cos-pitch)
                       (- (Math/sin pitch))
                       (* (Math/cos yaw) cos-pitch))
          reference (if (> (Math/abs (.-y forward)) 0.9)
                      (V3. 1.0 0.0 0.0)
                      (V3. 0.0 1.0 0.0))
          right (V3/normalize (V3/cross forward reference))]
      [right (V3/normalize (V3/cross right forward))])
    [(V3. 1.0 0.0 0.0) (V3. 0.0 0.0 1.0)]))

(defn- hand-origin-center [anchor geometry view-ctx]
  "Resolve the Railgun charge at the caster's rendered hand.

   The server anchor remains the fallback for remote viewers. For the local
   caster, use the interpolated hand center supplied by the MC adapter and
   main's first-person charge offsets; this keeps the billboard off the
   crosshair while preserving the remote-viewer network anchor."
  (if (and view-ctx
           (true? (:hand-origin? geometry))
           (:player-uuid view-ctx)
           (= (str (:player-uuid view-ctx))
              (str (:source-player-id geometry)))
           (number? (:x view-ctx))
           (number? (:y view-ctx))
           (number? (:z view-ctx)))
    (let [[right up] (view-basis view-ctx)
          forward (V3/normalize (V3/cross right up))
          hand (V3. (double (:x view-ctx))
                    (double (:y view-ctx))
                    (double (:z view-ctx)))]
      (V3/add hand
              (V3/add (V3/scale right 0.26)
                      (V3/add (V3/scale up -0.15)
                              (V3/scale forward 0.24)))))
    (v3-from anchor)))

(defn- color-rgba
  "A colour in this file's canonical [r g b a] form, accepting the {:r :g
   :b :a} spelling the material path also uses.

   Channels pass through unconverted. Running them through number-or would
   turn every [0 255 0 255] the content writes into [0.0 255.0 0.0 255.0],
   a representation change in the neutral ABI for no gain -- only the alpha
   this namespace computes needs to be a number of its own choosing."
  [color]
  (cond
    (and (vector? color) (<= 3 (count color)))
    [(or (nth color 0) 255) (or (nth color 1) 255)
     (or (nth color 2) 255) (or (nth color 3 255) 255)]
    (map? color)
    [(or (:r color) 255) (or (:g color) 255) (or (:b color) 255) (or (:a color) 255)]
    :else nil))

(defn- particle-alpha
  "An :alpha that is a number, or a {:min :max} range resolved
   deterministically from `seed` -- a range re-rolled per frame would make
   one particle flicker instead of holding the value it was spawned with."
  [alpha seed]
  (cond
    (number? alpha) (double alpha)
    (map? alpha) (let [lo (double (number-or (:min alpha) 0.0))
                       hi (double (number-or (:max alpha) lo))]
                   (if (<= hi lo)
                     lo
                     (+ lo (* (- hi lo) (.nextDouble (java.util.Random. (long seed)))))))
    :else nil))

(defn- fade-envelope
  "The pre-V4 particle alpha envelope: ramp in over :fade-in-ticks, hold,
   then ramp out over the last :fade-out-ticks of :life-ticks. Both ends are
   optional and a particle with neither holds full alpha for its life."
  ^double [particle ^double age]
  (let [life (double (number-or (:life-ticks particle) 0.0))
        fade-in (double (number-or (:fade-in-ticks particle) 0.0))
        fade-out (double (number-or (:fade-out-ticks particle) 0.0))
        in (if (and (pos? fade-in) (< age fade-in)) (/ age fade-in) 1.0)
        out (if (and (pos? life) (pos? fade-out) (> age (- life fade-out)))
              (/ (- life age) fade-out)
              1.0)]
    (max 0.0 (min 1.0 (* in out)))))

(defn- particle-color
  "The particle's own colour with its alpha envelope applied.

   Both were dropped for as long as :particle was a free-form :any map that
   this plan read six keys off: an emitter drew its MATERIAL colour at full
   opacity, so content carrying the original's tint and fade -- teleport
   marker's green at 153-204 alpha with a 5/20 fade pair -- rendered white
   and never faded."
  [particle fallback ^double age seed]
  (let [base (or (color-rgba (:color particle)) (color-rgba fallback) default-color)
        declared (particle-alpha (:alpha particle) seed)
        alpha0 (double (or declared (nth base 3)))
        faded (* alpha0 (fade-envelope particle age))]
    [(nth base 0) (nth base 1) (nth base 2)
     (long (Math/round ^double (max 0.0 (min 255.0 faded))))]))

(defn- layout-column
  "cn.li.vfx.layout's :cols entry for an attribute, or nil if the layout
   dead-stripped it. Read out of the plain map the batch carries rather
   than via cn.li.vfx.layout/column -- platform-shared must not gain a
   dependency on vfx-core just to do a get-in."
  [layout attr]
  (get-in layout [:cols attr]))

(defn- packed-rgba
  "Unpack the single int column cn.li.vfx.layout packs a :color attribute
   into. The layout deliberately packs 4 channels into one int rather than
   4 columns, so the renderer is where they come apart again."
  [^long packed]
  [(bit-and (bit-shift-right packed 16) 0xFF)
   (bit-and (bit-shift-right packed 8) 0xFF)
   (bit-and packed 0xFF)
   (bit-and (bit-shift-right packed 24) 0xFF)])

(defn- particle-ops
  "Expand the bounded Java particle SoA into billboard-like world quads.

   The neutral ABI deliberately carries positions/colors only; camera-facing
   orientation remains a version renderer concern. A stable XZ-facing quad is
   therefore emitted here, which every existing mc-* quad backend can consume
   without importing Minecraft classes into VFX core.

   Reads cn.li.vfx.layout's columns off the ParticleColumns the emitter
   stack actually fills. It used to take a ParticleBuffer -- a class with no
   producer anywhere in the repo -- behind an `instance?` guard, so every
   emitter batch failed the guard and drew nothing. ParticleColumns is its
   successor; the guard was the last thing still naming the predecessor.

   Every column but :position is optional, because cn.li.vfx.layout
   dead-strips any attribute no module writes: an emitter that never varies
   its size simply has no :size column, and takes the material's size."
  [^ParticleColumns particles layout material]
  (let [n (min (.size particles) (.capacity particles))
        cap (long (:capacity layout))
        ^floats fs (.floats particles)
        ^ints is (.ints particles)
        ;; No :position column means no module ever wrote one, so
        ;; there is nothing to place a quad at -- n collapses to 0 rather
        ;; than destructuring nil into three nil column indices.
        pos (layout-column layout :position)
        n (if (seq pos) n 0)
        [px py pz] pos
        size-col (first (layout-column layout :size))
        alpha-col (first (layout-column layout :alpha))
        color-col (first (layout-column layout :color))
        age-col (first (layout-column layout :age))
        life-col (first (layout-column layout :lifetime))
        spec (or (:particle material) {})
        default-half (max 0.001 (number-or (or (:size spec) (:scale spec)) 0.1))
        base (or (color-rgba (:color spec)) default-color)
        base-alpha (double (nth base 3))
        texture (or (:texture spec) default-texture)
        at (fn ^double [col ^long i] (double (aget fs (+ (* (long col) cap) i))))]
    (mapv (fn [i]
            (let [i (long i)
                  x (at px i) y (at py i) z (at pz i)
                  half (max 0.001 (if size-col (at size-col i) default-half))
                  rgb (if color-col
                        (packed-rgba (long (aget is (+ (* (long color-col) cap) i))))
                        base)
                  ;; The per-particle :alpha column and the material's fade
                  ;; envelope multiply rather than override each other: the
                  ;; column is the particle's own spawned opacity, the
                  ;; envelope is the emitter-wide ramp in and out.
                  a0 (if alpha-col (at alpha-col i) (double (nth rgb 3)))
                  a0 (if color-col a0 (min a0 base-alpha))
                  env (if (and age-col life-col)
                        (fade-envelope (assoc spec :life-ticks (at life-col i))
                                       (at age-col i))
                        1.0)
                  a (long (Math/round (max 0.0 (min 255.0 (* a0 env)))))]
              {:kind :quad
               :p0 (V3. (- x half) y (- z half))
               :p1 (V3. (- x half) y (+ z half))
               :p2 (V3. (+ x half) y (+ z half))
               :p3 (V3. (+ x half) y (- z half))
               :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
               :texture texture
               :color [(nth rgb 0) (nth rgb 1) (nth rgb 2) a]}))
          (range n))))

(defn- marker-quad [anchor color particle geometry view-ctx]
  (let [chance (number-or (:chance geometry) 1.0)
        seed (long (hash [anchor (:age geometry)]))
        spawn? (or (>= chance 1.0)
                   (and (pos? chance)
                        (< (.nextDouble (java.util.Random. seed)) chance)))
        center (hand-origin-center anchor geometry view-ctx)
        center (V3/add center (V3. 0.0 (number-or (:anchor-offset-y geometry) 0.0) 0.0))
        half (max 0.001 (number-or (or (:size particle) (:scale particle)) 0.08))
        texture (animated-texture particle)
        ;; The alpha roll is seeded on the anchor ALONE, not on the
        ;; spawn seed above, which folds in :age -- re-rolling a
        ;; {:min :max} alpha every frame would make one particle flicker
        ;; instead of holding the value it spawned with.
        color (particle-color particle color
                              (max 0.0 (number-or (or (:age particle)
                                                      (:age geometry))
                                                  0.0))
                              (hash anchor))
        [right up] (view-basis view-ctx)
        side (V3/scale right half)
        lift (V3/scale up half)
        p0 (V3/sub (V3/sub center side) lift)
        p1 (V3/add (V3/sub center side) lift)
        p2 (V3/add (V3/add center side) lift)
        p3 (V3/sub (V3/add center side) lift)]
    (if spawn?
      [{:kind :quad
        :p0 p0 :p1 p1 :p2 p2 :p3 p3
        :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
        :texture texture :color color}]
      [])))

(def ^:private teleport-marker-frame-count 7)
(def ^:private teleport-marker-texture-prefix
  "academy:textures/effects/tp_mark/")
(def ^:private teleport-marker-eye-height 1.62)

;; The main branch renders EntityTPMarking as a seven-frame, textured biped.
;; Keep its geometry in the neutral adapter so all Minecraft versions consume
;; the same frame ABI; only the final render type remains platform-specific.
(def ^:private teleport-marker-parts
  [{:hw 0.25 :hh 0.25 :hd 0.25 :cx 0.0 :cy 0.25
    :front [0.125 0.25 0.25 0.5] :back [0.375 0.5 0.25 0.5]
    :right [0.0 0.125 0.25 0.5] :left [0.25 0.375 0.25 0.5]
    :top [0.125 0.25 0.0 0.25] :bottom [0.25 0.375 0.0 0.25]}
   {:hw 0.28125 :hh 0.28125 :hd 0.28125 :cx 0.0 :cy 0.25
    :front [0.625 0.75 0.25 0.5] :back [0.875 1.0 0.25 0.5]
    :right [0.5 0.625 0.25 0.5] :left [0.75 0.875 0.25 0.5]
    :top [0.625 0.75 0.0 0.25] :bottom [0.75 0.875 0.0 0.25]}
   {:hw 0.25 :hh 0.375 :hd 0.125 :cx 0.0 :cy -0.375
    :front [0.3125 0.4375 0.5 0.875] :back [0.5 0.625 0.5 0.875]
    :right [0.25 0.3125 0.5 0.875] :left [0.4375 0.5 0.5 0.875]
    :top [0.3125 0.4375 0.5 0.625] :bottom [0.3125 0.4375 0.75 0.875]}
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx 0.375 :cy -0.375
    :front [0.6875 0.75 0.5 0.875] :back [0.75 0.8125 0.5 0.875]
    :right [0.625 0.6875 0.5 0.875] :left [0.8125 0.875 0.5 0.875]
    :top [0.6875 0.75 0.5 0.625] :bottom [0.6875 0.75 0.75 0.875]}
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx -0.375 :cy -0.375
    :front [0.5625 0.625 0.5 0.875] :back [0.5 0.5625 0.5 0.875]
    :right [0.5 0.5625 0.5 0.875] :left [0.5625 0.625 0.5 0.875]
    :top [0.5625 0.625 0.5 0.625] :bottom [0.5625 0.625 0.75 0.875]}
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx 0.125 :cy -1.125
    :front [0.0625 0.125 0.5 0.875] :back [0.0 0.0625 0.5 0.875]
    :right [0.0 0.0625 0.5 0.875] :left [0.0625 0.125 0.5 0.875]
    :top [0.0625 0.125 0.5 0.625] :bottom [0.0625 0.125 0.75 0.875]}
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx -0.125 :cy -1.125
    :front [0.1875 0.25 0.5 0.875] :back [0.125 0.1875 0.5 0.875]
    :right [0.125 0.1875 0.5 0.875] :left [0.1875 0.25 0.5 0.875]
    :top [0.1875 0.25 0.5 0.625] :bottom [0.1875 0.25 0.75 0.875]}])

(defn- teleport-marker-face-quads [texture center part f r u color]
  (let [{:keys [hw hh hd front back right left top bottom]} part
        qf (fn [normal normal-half tangent-a tangent-a-half tangent-b tangent-b-half uv]
             (let [face-center (V3/add center (V3/scale normal normal-half))
                   side-a (V3/scale tangent-a tangent-a-half)
                   side-b (V3/scale tangent-b tangent-b-half)]
               {:kind :quad
                :p0 (V3/add (V3/sub face-center side-a) side-b)
                :p1 (V3/sub (V3/sub face-center side-a) side-b)
                :p2 (V3/sub (V3/add face-center side-a) side-b)
                :p3 (V3/add (V3/add face-center side-a) side-b)
                :u0 (nth uv 0) :u1 (nth uv 1)
                :v0 (nth uv 2) :v1 (nth uv 3)
                :texture texture :color color :no-depth-test? true}))]
    [(qf f hd r hw u hh front)
     (qf (V3/scale f -1.0) hd r hw u hh back)
     (qf r hw f hd u hh right)
     (qf (V3/scale r -1.0) hw f hd u hh left)
     (qf u hh r hw f hd top)
     (qf (V3/scale u -1.0) hh r hw f hd bottom)]))

(defn- teleport-marker-ops [geometry material]
  (let [position (v3-from (:position geometry))
        direction (v3-from (:direction geometry))
        dx (.-x direction)
        dz (.-z direction)
        horizontal (Math/sqrt (+ (* dx dx) (* dz dz)))
        [look-x look-z] (if (> horizontal 1.0e-8)
                          [(/ dx horizontal) (/ dz horizontal)]
                          [0.0 1.0])
        ;; Main's MarkRender faces the marker back toward the caster.
        forward (V3. (- look-x) 0.0 (- look-z))
        right (V3. (.-z forward) 0.0 (- (.-x forward)))
        up (V3. 0.0 1.0 0.0)
        anchor (V3. (.-x position)
                    (+ (.-y position) teleport-marker-eye-height)
                    (.-z position))
        age (number-or (:age geometry) 0.0)
        frame (mod (long (Math/floor (/ age 2.5))) teleport-marker-frame-count)
        texture (str teleport-marker-texture-prefix frame ".png")
        color (or (material-color material) default-color)]
    (vec (mapcat (fn [part]
                   (let [center (V3. (+ (.-x anchor) (number-or (:cx part) 0.0))
                                     (+ (.-y anchor) (number-or (:cy part) 0.0))
                                     (.-z anchor))]
                     (teleport-marker-face-quads texture center part
                                                  forward right up color)))
                 teleport-marker-parts))))
(defn- first-field [fields keys]
  (some (fn [key]
          (let [value (get fields key)]
            (when (some? value) value)))
        keys))

(defn- point-chain-ops [points color]
  (let [points (vec (take 256 points))]
    (mapv (fn [[p1 p2]] (line-op p1 p2 color))
          (partition 2 1 points))))

(defn- typed-vfx-ops
  "Lower an explicitly typed-vfx fallback without dropping it.

  Leaf components that do not yet have a specialized sampler still cross the
  same neutral ABI. Common geometric fields are preserved as lines/rings; a
  bounded marker quad is used only when no geometry can be inferred. This is
  intentionally visible and deterministic, unlike the old empty-plan path."
  [geometry color]
  (let [fields (or (:fields geometry) {})
        start (first-field fields [:start :from :origin :position :anchor :base])
        end (first-field fields [:end :to :target])
        center (first-field fields [:center :position :origin :anchor :base])
        radius (first-field fields [:radius :size :width])
        points (first-field fields [:points :path :vertices])]
    (cond
      (and start end) [(line-op start end color)]
      (and (sequential? points) (> (count points) 1)) (point-chain-ops points color)
      (and center (number? radius)) (ring-ops {:center center :radius radius :segments 16} color)
      :else (marker-quad center color nil nil nil))))

(def ^:private vortex-ring-segments 20)
(def ^:private vortex-divisions 40)
(def ^:private vortex-texture "academy:textures/effects/tornado_ring.png")

(defn- vortex-range-value [value fallback]
  (if (map? value)
    (let [lo (number-or (or (:min value) (get value "min")) fallback)
          hi (number-or (or (:max value) (get value "max")) lo)]
      (max lo hi))
    (number-or value fallback)))

(defn- vortex-ring-stack [height seed]
  (let [height (max 0.1 (double height))
        step (/ height (double vortex-divisions))
        rng (java.util.Random. (long (hash [:storm-wing-vortex seed])))]
    (loop [y 0.0 rings []]
      (if (>= y height)
        rings
        (let [next-y (+ y (* step (+ 1.0 (* 0.2 (.nextGaussian rng)))))
              y* (max (+ y 0.01) next-y)
              ring {:y y*
                    :w (* step (+ 1.8 (* 0.4 (.nextDouble rng))))
                    :phase (* 360.0 (.nextDouble rng))
                    :scale (+ 0.9 (* 0.3 (.nextDouble rng)))}
              rings* (conj rings ring)]
          (recur y* (if (< (.nextDouble rng) 0.35)
                      (conj rings* (assoc ring
                                          :phase (* 360.0 (.nextDouble rng))
                                          :scale (+ 1.2 (* 0.5 (.nextDouble rng)))))
                      rings*)))))))

(defn- vortex-outer-linear [yaw phi x y z]
  (let [cy (Math/cos (- (double yaw)))
        sy (Math/sin (- (double yaw)))
        cp (Math/cos (double phi))
        sp (Math/sin (double phi))
        qy (- (* (double y) cp) (* (double z) sp))
        qz (+ (* (double y) sp) (* (double z) cp))]
    [(+ (* (double x) cy) (* qz sy))
     qy
     (- (* qz cy) (* (double x) sy))]))

(defn- vortex-linear [yaw phi sep-y sep-z]
  (let [cy (Math/cos (Math/toRadians (double sep-y)))
        sy (Math/sin (Math/toRadians (double sep-y)))
        cz (Math/cos (Math/toRadians (double sep-z)))
        sz (Math/sin (Math/toRadians (double sep-z)))]
    (fn [x y z]
      (let [a (- (* (double x) cz) (* (double y) sz))
            b (+ (* (double x) sz) (* (double y) cz))
            c (- (* (double z) cy) (* a sy))
            d (+ (* a cy) (* (double z) sy))]
        (vortex-outer-linear yaw phi d b c)))))

(defn- vortex-add [^V3 base [x y z]]
  (V3. (+ (.-x base) (double x))
       (+ (.-y base) (double y))
       (+ (.-z base) (double z))))

(defn- vortex-ring-ops [^V3 origin linear alpha age height seed size displacement-scale]
  (let [rings (vortex-ring-stack height seed)
        axis (vortex-linear 0.0 0.0 0.0 0.0)
        [ux uy uz] (axis 0.0 1.0 0.0)
        color {:r 255 :g 255 :b 255
               :a (max 0 (min 255 (int (* 255.0 (double alpha) 0.7))))}
        circle (mapv (fn [i]
                       (let [rad (* 2.0 Math/PI (/ (double i)
                                                    (double vortex-ring-segments)))]
                         [(Math/sin rad) (Math/cos rad)]))
                     (range vortex-ring-segments))]
    (mapcat
     (fn [{:keys [y w phase scale]}]
       (let [ny (/ (double y) (max 0.1 (double height)))
             sway (* (+ 0.3 (Math/pow (Math/abs (* 2.0 ny)) 1.4))
                     (double size) (double displacement-scale))
             radius (* (+ 0.5 (* 0.3 (Math/sin (+ (* ny 7.0)
                                                  (* 0.2 (double age))
                                                  phase)))
                        (* 0.5 (Math/pow (* 1.5 ny) 2.0))
                        (Math/cos (+ (* ny 11.0)
                                     (* 0.2 (double age))
                                     (* 0.37 phase))))
                     (double size) (double scale))
             dx (* (Math/sin (+ (* ny 9.0) (* 0.2 (double age)) phase)) sway)
             dz (* (Math/cos (+ (* ny 7.0) (* 0.2 (double age)) phase)) sway)
             edges (mapv (fn [[s c]]
                           (linear (+ dx (* s radius)) y (+ dz (* c radius))))
                         circle)
             hw (* 0.5 (double w))
             scroll (- (+ (* 0.1 (+ 1.0 (* 0.5 ny)) (double age))
                          (* 0.01 phase))
                       (Math/floor (+ (* 0.1 (+ 1.0 (* 0.5 ny)) (double age))
                                      (* 0.01 phase))))]
         (map (fn [i]
                (let [[x0 y0 z0] (nth edges i)
                      [x1 y1 z1] (nth edges (rem (inc i) vortex-ring-segments))
                      [xux xuy xuz] [(+ x0 (* ux hw)) (+ y0 (* uy hw)) (+ z0 (* uz hw))]
                      [xlx xly xlz] [(- x0 (* ux hw)) (- y0 (* uy hw)) (- z0 (* uz hw))]
                      [xrx xry xrz] [(+ x1 (* ux hw)) (+ y1 (* uy hw)) (+ z1 (* uz hw))]
                      [xll xlyy xllz] [(- x1 (* ux hw)) (- y1 (* uy hw)) (- z1 (* uz hw))]
                      p0 (vortex-add origin [xux xuy xuz])
                      p1 (vortex-add origin [xlx xly xlz])
                      p2 (vortex-add origin [xll xlyy xllz])
                      p3 (vortex-add origin [xrx xry xrz])
                      u0 (- (/ (double i) vortex-ring-segments) scroll)]
                  {:kind :quad :p0 p0 :p1 p1 :p2 p2 :p3 p3
                   :u0 u0 :u1 (+ u0 (/ 1.0 vortex-ring-segments))
                   :v0 0.0 :v1 1.0 :texture vortex-texture :color color}))
              (range vortex-ring-segments))))
     rings)))

(defn- vortex-column-ops [geometry view-ctx]
  (let [orientation (or (:orientation geometry) {})
        view (or (:hand-center-pos view-ctx) view-ctx)
        source (some-> (:source-player-id geometry) str)
        own? (and source (:player-uuid view)
                   (= source (str (:player-uuid view))))
        base0 (v3-from (:base geometry))
        base (if (and own? (number? (:player-x view))
                       (number? (:player-y view)) (number? (:player-z view)))
               (V3. (double (:player-x view))
                    (double (:player-y view))
                    (double (:player-z view)))
               base0)
        yaw (if (and own? (number? (:player-body-yaw-rad view)))
              (double (:player-body-yaw-rad view))
              0.0)
        pitch (if (and own? (number? (:player-pitch-rad view)))
                (double (:player-pitch-rad view))
                0.0)
        phi (- (* 0.2 pitch)
               (Math/toRadians (number-or (:back-tilt-degrees orientation) 70.0)))
        offset (v3-from (or (:anchor-offset orientation) [0.0 1.6 0.0]))
        pre (v3-from (or (:pre-translation orientation) [0.0 0.2 -0.5]))
        local (v3-from (or (:local-offset orientation) [0.0 0.0 0.0]))
        [hx hy hz] (vortex-outer-linear yaw phi (.-x pre) (.-y pre) (.-z pre))
        [lx ly lz] (vortex-outer-linear yaw phi (.-x local) (.-y local) (.-z local))
        origin (vortex-add base [(+ (.-x offset) hx lx)
                                 (+ (.-y offset) hy ly)
                                 (+ (.-z offset) hz lz)])
        linear (vortex-linear yaw phi
                              (number-or (:separation-y-degrees orientation) 0.0)
                              (number-or (:separation-z-degrees orientation) 0.0))
        fade (max 0.0 (min 1.0 (number-or (:fade-ratio geometry) 1.0)))
        size (max 0.0 (number-or (:size geometry) 0.16))
        displacement-scale (max 0.0 (number-or (:displacement-scale geometry) 2.0))
        alpha (* (max 0.0 (min 1.0 (number-or (:alpha geometry) 1.0))) fade)]
    (vortex-ring-ops origin linear alpha
                     (number-or (:age geometry) 0.0)
                     (number-or (:height geometry) 2.0)
                     (long (or (:seed geometry) 0))
                     size displacement-scale)))

(defn- plasma-ranged ^double [^java.util.Random rng ^double lo ^double hi]
  (+ lo (* (.nextDouble rng) (- hi lo))))

(defn- plasma-ball-spec
  "NOTE: the four size/spread/amp params are deliberately UNHINTED. Clojure
   refuses to compile a fn that mixes primitive hints with more than four
   args ('fns taking primitives support only 4 or fewer args'), and this one
   takes five counting rng. The hints bought nothing anyway -- the return is
   a map, so there is no primitive return path, and both call sites below
   pass double literals that plasma-ranged (3 args, still hinted) receives
   as primitives regardless."
  [^java.util.Random rng size-lo size-hi spread amp-scale]
  (let [size (plasma-ranged rng size-lo size-hi)
        cx (plasma-ranged rng (- spread) spread)
        cy (plasma-ranged rng (- spread) spread)
        cz (plasma-ranged rng (- spread) spread)
        h-amp (* (plasma-ranged rng 1.4 2.0) amp-scale size)
        h-speed (plasma-ranged rng 0.5 0.7)
        h-phase (plasma-ranged rng 0.0 (* 2.0 Math/PI))
        v-amp (* (plasma-ranged rng 1.4 2.0) amp-scale size)
        v-speed (plasma-ranged rng 0.5 0.7)
        v-phase (plasma-ranged rng 0.0 (* 2.0 Math/PI))]
    {:size size :cx cx :cy cy :cz cz
     :h-amp h-amp :h-speed h-speed :h-phase h-phase
     :v-amp v-amp :v-speed v-speed :v-phase v-phase}))

(defn- plasma-body-ops [geometry]
  (let [center (v3-from (:center geometry))
        age (number-or (:age geometry) 0.0)
        seconds (* 0.05 (double age))
        alpha (max 0.0 (min 1.0 (number-or (:alpha geometry) 0.0)))
        seed (long (or (:seed geometry) 0))
        rng (java.util.Random. seed)
        specs (vec (concat
                    (repeatedly 4 #(plasma-ball-spec rng 1.0 1.5 3.0 1.0))
                    (repeatedly (+ 4 (.nextInt rng 2))
                      #(plasma-ball-spec rng 0.1 0.3 3.0 0.35))))
        balls (mapv (fn [{:keys [size cx cy cz h-amp h-speed h-phase
                                  v-amp v-speed v-phase]}]
                      (let [hp (- (* h-speed seconds) h-phase)
                            vp (- (* v-speed seconds) v-phase)]
                        {:x (+ (.-x center) cx (* h-amp (Math/sin hp)))
                         :y (+ (.-y center) cy (* v-amp (Math/sin vp)))
                         :z (+ (.-z center) cz (* h-amp (Math/cos hp)))
                         :size size}))
                    specs)]
    [{:kind :plasma-body :center center :alpha alpha :balls balls}]))
(defn neutral-op->plan
  "Return the mc-* geometry plan for one neutral draw-batch operation.

   Particle and typed-vfx payloads are lowered through explicit neutral rules;
   no legacy payload is read and no recognized operation is silently discarded.

   Optional `view-ctx` is the presentation-frame-context. Its
   `:hand-center-pos` applies main ViewOptimize hand-origin translation to
   `:arc`; its `:camera-pos` anchors first-person trajectory geometry."
  ([op] (neutral-op->plan op nil))
  ([op view-ctx]
   (let [hand-view-ctx (or (:hand-center-pos view-ctx) view-ctx)
         op (arc-geometry/with-hand-origin-view op hand-view-ctx)]
     (when (and (map? op) (= :draw-batch (:operation op)))
       (let [geometry (or (:geometry op) {})
             material (or (:material op) {})
             color (material-color material)
             primitive (:primitive op)
             ops (case primitive
                   :line (case (:kind geometry)
                           :ring (ring-ops geometry color)
                           :target-box (target-box-ops geometry color)
                           :beam (line-ops {:start (:start geometry)
                                            :end (:end geometry)} color)
                           (line-ops geometry color))
                   :quad (case (:kind geometry)
                           :beam (beam-ops geometry material)
                           :ray-fan (ray-fan-ops geometry material)
                           :trajectory (trajectory-ops geometry material view-ctx)
                           :arc (arc-geometry/arc-quad-ops geometry material color)
                           :vortex-column (vortex-column-ops geometry view-ctx)
                           :plasma-body (plasma-body-ops geometry)
                           :teleport-marker (teleport-marker-ops geometry material)
                           :surround-arc (arc-geometry/surround-arc-quad-ops geometry material color)
                           :emitter (marker-quad (:anchor geometry) color (:particle geometry)
                                                 geometry view-ctx)
                           :particle-trail (particle-trail-ops geometry material view-ctx)
                           (quad-ops geometry color material))
                   :particle (let [particles (:particles op)]
                               (if (instance? ParticleColumns particles)
                                 (particle-ops particles (:layout op) material)
                                 []))
                   :typed-vfx (typed-vfx-ops geometry color)
                   [])]
         {:ops ops})))))
