(ns cn.li.platform.neutral.vfx-render-plan
  "Translate the Minecraft-free VFX draw ABI into a version geometry plan.

   VFX Core owns evaluated, typed-neutral draw operations.  The existing
   mc-* geometry modules remain responsible for Minecraft buffer state, so
   this adapter only expands line/beam/ring/quad/particle geometry into their
   long-lived {:ops [...]} plan shape.  It imports no Minecraft class."
  (:require [cn.li.platform.neutral.arc-geometry :as arc-geometry])
  (:import [cn.li.mcmod.math V3]
           [cn.li.mcmod.runtime.vfx ParticleBuffer]))

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

(defn- line-ops [geometry color]
  (let [p1 (or (:p1 geometry) (:start geometry) (:from geometry))
        p2 (or (:p2 geometry) (:end geometry) (:to geometry))]
    (if (and p1 p2) [(line-op p1 p2 color)] [])))

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

(defn- particle-ops
  "Expand the bounded Java particle SoA into billboard-like world quads.

  The neutral ABI deliberately carries positions/colors only; camera-facing
  orientation remains a version renderer concern. A stable XZ-facing quad is
  therefore emitted here, which every existing mc-* quad backend can consume
  without importing Minecraft classes into VFX core."
  [^ParticleBuffer particles material]
  (let [n (min (.size particles) (.capacity particles))
        spec (or (:particle material) {})
        half (max 0.001 (number-or (or (:size spec) (:scale spec)) 0.1))
        texture (or (:texture spec) default-texture)]
    (mapv (fn [i]
            (let [x (double (aget (.positionX particles) i))
                  y (double (aget (.positionY particles) i))
                  z (double (aget (.positionZ particles) i))
                  color (aget (.color particles) i)]
              {:kind :quad
               :p0 (V3. (- x half) y (- z half))
               :p1 (V3. (- x half) y (+ z half))
               :p2 (V3. (+ x half) y (+ z half))
               :p3 (V3. (+ x half) y (- z half))
               :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
               :texture texture :color color}))
          (range n))))

(defn- marker-quad [anchor color]
  (let [center (v3-from anchor)
        half 0.08
        x (.-x center)
        y (.-y center)
        z (.-z center)]
    [{:kind :quad
      :p0 (V3. (- x half) y (- z half))
      :p1 (V3. (- x half) y (+ z half))
      :p2 (V3. (+ x half) y (+ z half))
      :p3 (V3. (+ x half) y (- z half))
      :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
      :texture default-texture :color color}]))

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
      :else (marker-quad center color))))

(defn neutral-op->plan
  "Return the mc-* geometry plan for one neutral draw-batch operation.

   Particle and typed-vfx payloads are lowered through explicit neutral rules;
   no legacy payload is read and no recognized operation is silently discarded.

   Optional `view-ctx` (presentation-world hand-center-pos) applies main's
   ViewOptimize hand-origin translation to `:arc` geometry before expansion."
  ([op] (neutral-op->plan op nil))
  ([op view-ctx]
   (let [op (arc-geometry/with-hand-origin-view op view-ctx)]
     (when (and (map? op) (= :draw-batch (:operation op)))
       (let [geometry (or (:geometry op) {})
             material (or (:material op) {})
             color (material-color material)
             primitive (:primitive op)
             ops (case primitive
                   :line (case (:kind geometry)
                           :ring (ring-ops geometry color)
                           :beam (line-ops {:start (:start geometry)
                                            :end (:end geometry)} color)
                           (line-ops geometry color))
                   :quad (case (:kind geometry)
                           :arc (arc-geometry/arc-quad-ops geometry material color)
                           (quad-ops geometry color material))
                   :particle (if-let [particles (:particle-buffer op)]
                               (if (instance? ParticleBuffer particles)
                                 (particle-ops particles material)
                                 [])
                               [])
                   :typed-vfx (typed-vfx-ops geometry color)
                   [])]
         {:ops ops})))))
